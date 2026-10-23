package org.sopt.solply_server.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.math.BigInteger;
import java.security.Key;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.UserRole;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.jwt.dto.AccessTokenPayload;
import org.sopt.solply_server.global.jwt.dto.RefreshTokenMaterial;
import org.sopt.solply_server.global.jwt.dto.RefreshTokenPayload;
import org.springframework.stereotype.Component;

/**
 * 토큰의 발급·재구성·검증. <b>DB를 모른다</b> — 여기서 통과한 토큰이 살아 있는지는 refresh 행이
 * 정하고, access는 애초에 그 질문을 하지 않는다.
 *
 * <p><b>재구성이 이 클래스의 중심이다.</b> {@link #serializeRefreshToken}이 발급과 유예 재구성
 * 양쪽에서 쓰이는 유일한 경로다. 두 경로가 서로 다른 코드로 같은 문자열을 만들려고 하면
 * 클레임 순서 하나만 어긋나도 바이트가 갈리고, 갈렸다는 사실은 클라이언트가 옛 토큰을 다시 보낼
 * 때에야 드러난다. 그래서 "같은 값이면 같은 문자열"을 규약이 아니라 <b>구조</b>로 만든다.
 *
 * <p>그 위에 서는 고정값이 셋이다 — <b>클레임 순서</b>(빌더 호출 순서가 곧 JSON 키 순서다),
 * <b>HS512</b>, <b>정수 초 NumericDate</b>. 셋 중 하나라도 바뀌면 그것은 새 형식이므로
 * {@link #TOKEN_FORMAT_VERSION}을 올려야 한다. 그래서 {@code ver}는 <b>access·refresh 양쪽 모두
 * 필수이고 지금 판과 정확히 같아야 한다</b> — 버전을 싣기만 하고 검사하지 않으면 판을 올려도
 * 옛 토큰이 계속 통과한다.
 *
 * <p><b>판을 올렸을 때 옛 토큰이 어디서 걸리는지를 헷갈리지 말 것.</b> 옛 {@code ver}를 실은
 * 토큰은 {@link #validateFormatVersion}이 <b>파싱 단계에서</b> {@code INVALID_TOKEN}으로 거절하므로
 * DB 판정까지 내려가지 않는다. {@code refresh_token.token_format_version}을 보는 분기
 * ({@code RefreshTokenService#loadAndVerify}·{@code #reissueWithinGrace})는 그와 <b>다른 경로</b>이고,
 * 토큰이 주장한 판과 행에 적힌 판이 갈리는 경우를 위한 안전장치다 — 정상적으로는 둘이 항상 같아
 * 실행되지 않는다.
 *
 * <p><b>시각 판정은 파서에 맡기지 않는다.</b> JJWT의 만료 검사는 스큐만큼 관대해서
 * {@code exp == now}와 스큐 안의 만료 토큰을 통과시킨다. 여기서 {@code exp <= now}를 스큐 없이
 * 다시 걸어 refresh 행의 상태 판정과 같은 규칙에 세운다 — {@code clock-skew-seconds}가 실제로
 * 완화하는 것은 {@code iat}(과 쓰지는 않는 {@code nbf}) 쪽뿐이다.
 *
 * <p><b>온라인 키 교체는 없다.</b> 키·issuer·audience가 바뀌면 이미 나간 토큰은 전부 거절된다.
 * 무중단 호환 경로가 아니라 전원 재로그인을 동반하는 전환이다.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    /**
     * 재구성이 뜻하는 클레임 집합의 판. refresh 행에 함께 저장되고, 유예 재구성은 <b>저장된 값이
     * 지금 판과 같을 때만</b> 성립한다.
     */
    public static final int TOKEN_FORMAT_VERSION = 1;

    private static final SignatureAlgorithm SIGNATURE_ALGORITHM = SignatureAlgorithm.HS512;

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_PLATFORM = "platform";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_FAMILY_ID = "fid";
    private static final String CLAIM_FORMAT_VERSION = "ver";

    private final Key accessKey;
    private final Key refreshKey;
    private final String issuer;
    private final String audience;
    private final long accessTokenExpireTime;
    private final long refreshTokenExpireTime;
    private final long clockSkewSeconds;
    private final Clock clock;

    public JwtTokenProvider(JwtProperties jwtProperties, Clock clock) {
        this.accessKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getAccessSecretKey()));
        this.refreshKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getRefreshSecretKey()));
        this.issuer = jwtProperties.getIssuer();
        this.audience = jwtProperties.getAudience();
        this.accessTokenExpireTime = jwtProperties.getAccessTokenExpireTime();
        this.refreshTokenExpireTime = jwtProperties.getRefreshTokenExpireTime();
        this.clockSkewSeconds = jwtProperties.getClockSkewSeconds();
        this.clock = clock;
    }

    /**
     * access 토큰 발급. 역할은 <b>발급 시점의 DB 값</b>이고 요청 시에는 그대로 믿는다 —
     * 그래서 권한 변경과 탈퇴가 반영되기까지 최대 access 수명(30분)이 걸린다. 즉시 차단이
     * 필요한 자리는 도메인 로직의 상태·소유권 검사이지 인증 단계가 아니다.
     *
     * <p>계열 ID를 싣는 이유는 로그아웃이다. 로그아웃은 "이 access를 낸 로그인"의 refresh만
     * 끊어야 하는데, 그 로그인을 가리키는 값이 토큰 안에 없으면 사용자의 모든 기기를 끊는
     * 수밖에 없다.
     */
    public String createAccessToken(Long userId, SocialPlatform platform, UserRole role, String familyId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .setIssuer(issuer)
                .setAudience(audience)
                .setSubject(String.valueOf(userId))
                .claim(CLAIM_TYPE, TokenType.ACCESS.claimValue())
                .claim(CLAIM_PLATFORM, platform.name())
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_FAMILY_ID, familyId)
                .claim(CLAIM_FORMAT_VERSION, TOKEN_FORMAT_VERSION)
                .setIssuedAt(secondsOf(now))
                .setExpiration(secondsOf(now.plusMillis(accessTokenExpireTime)))
                .signWith(accessKey, SIGNATURE_ALGORITHM)
                .compact();
    }

    /**
     * 새 refresh 토큰의 재료. 여기서 정해진 정수 초가 그대로 {@code refresh_token} 행에 저장되고,
     * 유예 재구성은 그 행을 읽어 {@link #serializeRefreshToken}을 다시 통과시킨다.
     */
    public RefreshTokenMaterial newRefreshTokenMaterial(
            Long userId, SocialPlatform platform, String familyId, String jwtId) {
        Instant now = clock.instant();
        return new RefreshTokenMaterial(
                userId,
                platform,
                familyId,
                jwtId,
                TOKEN_FORMAT_VERSION,
                now.getEpochSecond(),
                now.plusMillis(refreshTokenExpireTime).getEpochSecond()
        );
    }

    /**
     * 재료 → 문자열. <b>발급과 유예 재구성이 공유하는 유일한 경로다.</b>
     *
     * <p>빌더 호출 순서가 JSON 키 순서이므로 이 메서드의 줄 순서를 바꾸면 같은 재료가 다른
     * 문자열이 된다. 순서를 바꿔야 한다면 그것은 새 형식이고 {@link #TOKEN_FORMAT_VERSION}이
     * 함께 올라가야 한다.
     */
    public String serializeRefreshToken(RefreshTokenMaterial material) {
        if (material.formatVersion() != TOKEN_FORMAT_VERSION) {
            // 여기까지 내려온 것 자체가 호출자의 버그다 — 재구성 가능 여부는 행을 읽은 쪽이
            // 먼저 판정한다. 옛 형식으로 억지로 만든 문자열은 원본과 다르므로 내보내면 안 된다.
            throw new JwtTokenException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        return Jwts.builder()
                .setIssuer(issuer)
                .setAudience(audience)
                .setSubject(String.valueOf(material.userId()))
                .setId(material.jwtId())
                .claim(CLAIM_TYPE, TokenType.REFRESH.claimValue())
                .claim(CLAIM_PLATFORM, material.platform().name())
                .claim(CLAIM_FAMILY_ID, material.familyId())
                .claim(CLAIM_FORMAT_VERSION, material.formatVersion())
                .setIssuedAt(Date.from(Instant.ofEpochSecond(material.issuedAtEpochSecond())))
                .setExpiration(Date.from(Instant.ofEpochSecond(material.expiresAtEpochSecond())))
                .signWith(refreshKey, SIGNATURE_ALGORITHM)
                .compact();
    }

    public AccessTokenPayload parseAccessToken(String token) {
        Claims claims = parseAndValidate(token, accessKey, TokenType.ACCESS);
        return new AccessTokenPayload(
                requiredUserId(claims),
                requiredPlatform(claims),
                requiredRole(claims),
                requiredString(claims, CLAIM_FAMILY_ID)
        );
    }

    public RefreshTokenPayload parseRefreshToken(String token) {
        Claims claims = parseAndValidate(token, refreshKey, TokenType.REFRESH);
        String jwtId = claims.getId();
        if (jwtId == null || jwtId.isBlank()) {
            throw new JwtTokenException(ErrorCode.INVALID_TOKEN);
        }
        return new RefreshTokenPayload(
                requiredUserId(claims),
                requiredPlatform(claims),
                requiredString(claims, CLAIM_FAMILY_ID),
                jwtId,
                requiredExactInt(claims, CLAIM_FORMAT_VERSION),
                requiredNumericDate(claims, Claims.ISSUED_AT),
                requiredNumericDate(claims, Claims.EXPIRATION)
        );
    }

    /**
     * 서명·발급자·수신자·종류·형식 버전·시각을 한 번에 건다.
     *
     * <p><b>알고리즘을 헤더에서 다시 확인하는 이유.</b> 파서에 키를 주면 "서명 없음"은 막히지만
     * 같은 키로 HS256이라 주장하는 토큰은 그대로 통과한다. 우리 토큰은 전부 HS512이므로
     * 그 외의 값은 우리가 만들지 않은 것이다.
     *
     * <p><b>시각을 파서에 맡기지 않고 다시 보는 이유.</b> JJWT 0.11.5의 만료 검사는
     * {@code now − skew > exp}일 때만 예외를 던진다. 즉 <b>{@code exp == now}와 스큐 안의
     * 만료된 토큰이 통과한다.</b> 그 관대함은 refresh 행의 상태 판정({@code expires_at <= now}면
     * EXPIRED)과 어긋나므로, 같은 토큰이 JWT 단계는 통과하고 DB 단계는 만료로 읽히는 창이 생긴다.
     * 여기서 {@code exp <= now}를 <b>스큐 없이</b> 다시 걸어 두 판정을 같은 규칙에 세운다.
     *
     * <p>그래서 {@code clock-skew-seconds}가 실제로 완화하는 것은 <b>발급 시각 쪽뿐이다</b> —
     * 앞선 시계가 발급한 토큰({@code iat}가 미래)을 허용 오차 안에서 받아 주고, 쓰지는 않지만
     * {@code nbf}가 들어오면 파서가 같은 폭으로 본다. 만료에는 관여하지 않는다.
     *
     * <p>만료를 별도 분기로 잡는 것은 클라이언트가 <b>재발급하면 되는 상황</b>과 <b>다시 로그인해야
     * 하는 상황</b>을 구분할 수 있어야 하기 때문이다. refresh가 JWT 단계에서 만료됐다면 DB의
     * 재사용 판정으로 내려가지 않는다 — 만료는 공격의 신호가 아니다.
     */
    private Claims parseAndValidate(String token, Key key, TokenType expectedType) {
        Jws<Claims> jws;
        try {
            jws = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .requireIssuer(issuer)
                    .requireAudience(audience)
                    .require(CLAIM_TYPE, expectedType.claimValue())
                    .setAllowedClockSkewSeconds(clockSkewSeconds)
                    .setClock(() -> Date.from(clock.instant()))
                    .build()
                    .parseClaimsJws(token);
        } catch (ExpiredJwtException e) {
            throw expiredToken(expectedType);
        } catch (JwtException | IllegalArgumentException e) {
            throw new JwtTokenException(ErrorCode.INVALID_TOKEN);
        }

        if (!SIGNATURE_ALGORITHM.getValue().equals(jws.getHeader().getAlgorithm())) {
            throw new JwtTokenException(ErrorCode.INVALID_TOKEN);
        }

        Claims claims = jws.getBody();
        validateFormatVersion(claims);
        validateTimestamps(claims, expectedType);
        return claims;
    }

    /**
     * 형식 버전은 <b>access와 refresh 모두 필수이고 지금 판과 정확히 같아야 한다.</b>
     *
     * <p>refresh만 검사하면 access의 클레임 집합이 바뀌었을 때 옛 access가 조용히 통과한다 —
     * 새로 생긴 필수 클레임이 아직 없는 토큰이 "있는 것만 맞으면 통과"가 되는 자리다.
     * 값을 읽는 방식도 느슨하면 안 된다({@link #requiredExactInt}).
     */
    private void validateFormatVersion(Claims claims) {
        if (requiredExactInt(claims, CLAIM_FORMAT_VERSION) != TOKEN_FORMAT_VERSION) {
            throw new JwtTokenException(ErrorCode.INVALID_TOKEN);
        }
    }

    /**
     * {@code iat}·{@code exp}가 <b>정수 초 NumericDate</b>로 들어왔는지, 그리고 서로·현재와
     * 앞뒤가 맞는지.
     *
     * <p>형식을 따로 보는 이유는 JJWT가 관대하기 때문이다 — {@code Claims#getIssuedAt()}은
     * 문자열 날짜도 파싱해 Date로 돌려준다. 그렇게 통과한 토큰은 재구성 단계에서 다른 바이트가
     * 되므로, 우리 계약(정수 초)에 맞지 않는 표현은 여기서 거절한다.
     */
    private void validateTimestamps(Claims claims, TokenType expectedType) {
        long issuedAt = requiredNumericDate(claims, Claims.ISSUED_AT);
        long expiresAt = requiredNumericDate(claims, Claims.EXPIRATION);

        // 수명이 0 이하인 토큰은 우리가 만들지 않는다. 만료 검사만으로는 exp < iat인 토큰이
        // "아직 안 만료됐다"로 통과할 수 있다.
        if (expiresAt <= issuedAt) {
            throw new JwtTokenException(ErrorCode.INVALID_TOKEN);
        }

        long nowSeconds = clock.instant().getEpochSecond();
        if (expiresAt <= nowSeconds) {
            throw expiredToken(expectedType);
        }
        // 앞선 시계가 발급한 토큰은 허용 오차 안에서만 받는다. 그 밖의 미래 발급은 위조다.
        if (issuedAt > nowSeconds + clockSkewSeconds) {
            throw new JwtTokenException(ErrorCode.INVALID_TOKEN);
        }
    }

    private JwtTokenException expiredToken(TokenType expectedType) {
        return new JwtTokenException(expectedType == TokenType.ACCESS
                ? ErrorCode.EXPIRED_ACCESS_TOKEN
                : ErrorCode.EXPIRED_REFRESH_TOKEN);
    }

    /** 사용자 ID는 양수다. 0·음수는 우리가 발급한 적이 없는 값이라 형식만 맞는 위조로 본다. */
    private Long requiredUserId(Claims claims) {
        long userId;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            throw new JwtTokenException(ErrorCode.INVALID_TOKEN);
        }
        if (userId <= 0) {
            throw new JwtTokenException(ErrorCode.INVALID_TOKEN);
        }
        return userId;
    }

    private SocialPlatform requiredPlatform(Claims claims) {
        try {
            return SocialPlatform.valueOf(requiredString(claims, CLAIM_PLATFORM));
        } catch (IllegalArgumentException e) {
            throw new JwtTokenException(ErrorCode.INVALID_TOKEN);
        }
    }

    /**
     * 알 수 없는 역할 문자열은 <b>거절이다.</b> 예전에는 {@code null}로 보고 DB 조회로 폴백했지만
     * 그 폴백이 사라졌으므로, 여기서 삼키면 권한 없는 주체가 만들어진다.
     */
    private UserRole requiredRole(Claims claims) {
        try {
            return UserRole.valueOf(requiredString(claims, CLAIM_ROLE));
        } catch (IllegalArgumentException e) {
            throw new JwtTokenException(ErrorCode.INVALID_TOKEN);
        }
    }

    private String requiredString(Claims claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new JwtTokenException(ErrorCode.INVALID_TOKEN);
        }
        return s;
    }

    /**
     * 정수 클레임. <b>{@code Number#intValue()}를 쓰지 않는다</b> — 그 메서드는 {@code 1.9}를 1로
     * 자르고 {@code 4294967297}을 1로 감아 버려서, 우리가 쓴 적 없는 표현이 우리 값과 같아 보이게
     * 만든다. 정수 계열만 받고 int 범위를 벗어나면 거절한다.
     */
    private int requiredExactInt(Claims claims, String name) {
        Long value = integralValue(claims.get(name));
        if (value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new JwtTokenException(ErrorCode.INVALID_TOKEN);
        }
        return value.intValue();
    }

    /** NumericDate 클레임을 정수 초로 읽는다. 소수·문자열 표현은 우리 계약이 아니다. */
    private long requiredNumericDate(Claims claims, String name) {
        Long seconds = integralValue(claims.get(name));
        if (seconds == null) {
            throw new JwtTokenException(ErrorCode.INVALID_TOKEN);
        }
        return seconds;
    }

    /**
     * JSON 값이 <b>정확한 정수</b>면 그 값, 아니면 {@code null}.
     *
     * <p>Jackson은 JSON 정수를 크기에 따라 Integer·Long·BigInteger로, 소수는 Double·BigDecimal로
     * 만든다. 소수·문자열·{@code null}은 전부 거절 대상이다 — 값을 "해석"해서 받아 주기 시작하면
     * 같은 뜻의 서로 다른 표현이 생기고, 그 순간 재구성이 원본과 갈린다.
     */
    private Long integralValue(Object value) {
        if (value instanceof Integer i) {
            return i.longValue();
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Short s) {
            return s.longValue();
        }
        if (value instanceof Byte b) {
            return b.longValue();
        }
        if (value instanceof BigInteger bi) {
            try {
                return bi.longValueExact();
            } catch (ArithmeticException e) {
                return null;
            }
        }
        return null;
    }

    /** NumericDate는 정수 초다. 밀리초를 잘라 두지 않으면 같은 재료가 다른 문자열이 될 수 있다. */
    private Date secondsOf(Instant instant) {
        return Date.from(Instant.ofEpochSecond(instant.getEpochSecond()));
    }
}
