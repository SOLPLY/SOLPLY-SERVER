package org.sopt.solply_server.global.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.UserRole;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.jwt.dto.AccessTokenPayload;
import org.sopt.solply_server.global.jwt.dto.RefreshTokenMaterial;
import org.sopt.solply_server.global.jwt.dto.RefreshTokenPayload;
import org.sopt.solply_server.support.MutableClock;

/**
 * 토큰 검증의 <b>거절 목록</b>. 저장소를 타지 않는 계층이라 단위 테스트이고, 그래서 값싸다 —
 * 이 목록은 길어야 하고, 길어도 스위트를 느리게 만들지 않아야 한다.
 *
 * <p><b>거절 목록이 곧 계약이다.</b> "필수 클레임"은 문서의 표현이 아니라 없을 때 거절되는 동작이고,
 * 검사하지 않는 클레임은 실어도 없는 것과 같다. 아래 테스트 하나하나가 그 동작의 정의다.
 *
 * <p>키는 테스트 안에서 만든다 — 설정 파일의 실제 비밀 키를 읽지 않는다. HS512는 키가 512비트
 * 이상이어야 하므로 64바이트를 Base64로 싣는다.
 */
class JwtTokenProviderTest {

    private static final String ISSUER = "solply-server";
    private static final String AUDIENCE = "solply-app";
    private static final long SKEW_SECONDS = 30L;
    private static final Instant NOW = Instant.parse("2026-09-12T03:00:00Z");

    private static final String ACCESS_SECRET = base64Key('a');
    private static final String REFRESH_SECRET = base64Key('r');
    private static final Key ACCESS_KEY =
            Keys.hmacShaKeyFor(Decoders.BASE64.decode(ACCESS_SECRET));
    private static final Key REFRESH_KEY =
            Keys.hmacShaKeyFor(Decoders.BASE64.decode(REFRESH_SECRET));

    private MutableClock clock;
    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        clock = MutableClock.fixedUtc(NOW);
        provider = new JwtTokenProvider(properties(), clock);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 정상 경로
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void 발급한_access는_그대로_파싱된다() {
        String token = provider.createAccessToken(7L, SocialPlatform.KAKAO, UserRole.ADMIN, "fam-1");

        AccessTokenPayload payload = provider.parseAccessToken(token);

        assertThat(payload.userId()).isEqualTo(7L);
        assertThat(payload.platform()).isEqualTo(SocialPlatform.KAKAO);
        assertThat(payload.role()).isEqualTo(UserRole.ADMIN);
        assertThat(payload.familyId()).isEqualTo("fam-1");
    }

    /** 같은 재료는 <b>같은 바이트</b>가 된다 — 유예 재구성이 이 성질 하나 위에 서 있다. */
    @Test
    void 같은_재료는_항상_같은_문자열로_직렬화된다() {
        RefreshTokenMaterial material =
                provider.newRefreshTokenMaterial(7L, SocialPlatform.APPLE, "fam-1", "jti-1");

        String first = provider.serializeRefreshToken(material);
        clock.advance(Duration.ofHours(1));
        String second = provider.serializeRefreshToken(material);

        assertThat(second).isEqualTo(first);
        RefreshTokenPayload payload = provider.parseRefreshToken(first);
        assertThat(payload.jwtId()).isEqualTo("jti-1");
        assertThat(payload.issuedAtEpochSecond()).isEqualTo(material.issuedAtEpochSecond());
        assertThat(payload.expiresAtEpochSecond()).isEqualTo(material.expiresAtEpochSecond());
    }

    /** NumericDate는 정수 초다 — 밀리초를 들고 있으면 왕복에서 잘려 다른 문자열이 된다. */
    @Test
    void 발급_시각은_정수_초로_접힌다() {
        clock.setTo(NOW.plusMillis(750));

        RefreshTokenMaterial material =
                provider.newRefreshTokenMaterial(7L, SocialPlatform.KAKAO, "fam", "jti");

        assertThat(material.issuedAtEpochSecond()).isEqualTo(NOW.getEpochSecond());
    }

    /** 옛 형식 행을 억지로 직렬화하면 원본과 다른 문자열이 나간다 — 그래서 만들지 않는다. */
    @Test
    void 형식_버전이_다른_재료는_직렬화하지_않는다() {
        RefreshTokenMaterial stale = new RefreshTokenMaterial(
                7L, SocialPlatform.KAKAO, "fam", "jti",
                JwtTokenProvider.TOKEN_FORMAT_VERSION + 1,
                NOW.getEpochSecond(), NOW.getEpochSecond() + 60);

        assertThatThrownBy(() -> provider.serializeRefreshToken(stale))
                .isInstanceOf(JwtTokenException.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 서명·알고리즘·고정 클레임
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("서명과 고정 클레임")
    class SignatureAndFixedClaims {

        @Test
        void 다른_키로_서명한_토큰은_거절한다() {
            String forged = accessBuilder(claims -> {
            }).signWith(REFRESH_KEY, SignatureAlgorithm.HS512).compact();

            assertInvalid(forged);
        }

        /** access 키와 refresh 키가 갈려 있다 — 한쪽 토큰을 다른 쪽으로 쓸 수 없다. */
        @Test
        void refresh_토큰을_access로_파싱할_수_없다() {
            String refresh = provider.serializeRefreshToken(
                    provider.newRefreshTokenMaterial(7L, SocialPlatform.KAKAO, "fam", "jti"));

            assertThatThrownBy(() -> provider.parseAccessToken(refresh))
                    .isInstanceOf(JwtTokenException.class);
        }

        /**
         * <b>파서에 키를 주면 "서명 없음"은 막히지만, 같은 키로 HS256이라 주장하는 토큰은 통과한다.</b>
         * 우리 토큰은 전부 HS512이므로 그 외의 값은 우리가 만들지 않은 것이다.
         */
        @Test
        void HS512가_아닌_알고리즘은_거절한다() {
            String hs256 = accessBuilder(claims -> {
            }).signWith(ACCESS_KEY, SignatureAlgorithm.HS256).compact();

            assertInvalid(hs256);
        }

        @Test
        void issuer가_다르면_거절한다() {
            assertInvalid(access(claims -> claims.put("iss", "other-server")));
        }

        @Test
        void audience가_다르면_거절한다() {
            assertInvalid(access(claims -> claims.put("aud", "other-app")));
        }

        @Test
        void type이_다르면_거절한다() {
            assertInvalid(access(claims -> claims.put("type", "refresh")));
        }

        @Test
        void 필수_클레임이_없으면_거절한다() {
            for (String claim : new String[] {"iss", "aud", "sub", "type", "platform", "role",
                    "fid", "ver", "iat", "exp"}) {
                String token = access(claims -> claims.remove(claim));
                assertThatThrownBy(() -> provider.parseAccessToken(token))
                        .as("%s 누락", claim)
                        .isInstanceOf(JwtTokenException.class);
            }
        }

        /** refresh는 {@code jti}가 신원이다 — 없으면 행을 찾을 수 없다. */
        @Test
        void refresh에_jti가_없으면_거절한다() {
            String token = refresh(claims -> claims.remove("jti"));

            assertThatThrownBy(() -> provider.parseRefreshToken(token))
                    .isInstanceOf(JwtTokenException.class);
        }

        @Test
        void 알_수_없는_역할이나_플랫폼_문자열은_거절한다() {
            assertInvalid(access(claims -> claims.put("role", "SUPERUSER")));
            assertInvalid(access(claims -> claims.put("platform", "NAVER_X")));
            assertInvalid(access(claims -> claims.put("fid", "")));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 형식 버전
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("형식 버전 — 느슨하게 읽으면 검사하지 않는 것과 같다")
    class FormatVersion {

        @Test
        void 다른_판의_토큰은_access도_refresh도_거절한다() {
            assertInvalid(access(claims -> claims.put("ver", 2)));
            String staleRefresh = refresh(claims -> claims.put("ver", 2));
            assertThatThrownBy(() -> provider.parseRefreshToken(staleRefresh))
                    .isInstanceOf(JwtTokenException.class);
        }

        /**
         * <b>{@code Number#intValue()}였다면 통과했을 값들.</b> 그 메서드는 {@code 1.9}를 1로 자르고
         * {@code 4294967297}을 1로 감아, 우리가 쓴 적 없는 표현이 우리 값과 같아 보이게 만든다.
         */
        @Test
        void 정수가_아닌_표현의_버전은_거절한다() {
            assertInvalid(access(claims -> claims.put("ver", 1.0)));
            assertInvalid(access(claims -> claims.put("ver", 1.9)));
            assertInvalid(access(claims -> claims.put("ver", "1")));
            assertInvalid(access(claims -> claims.put("ver", 4_294_967_297L)));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 시각
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("시각 — 만료에는 스큐가 없다")
    class Timestamps {

        /**
         * <b>{@code exp == now}는 거절이다.</b> JJWT의 만료 검사는 {@code now − skew > exp}일 때만
         * 예외를 던져 이 토큰과 스큐 안의 만료 토큰을 통과시킨다. 그 관대함은 refresh 행의 상태 판정
         * ({@code expires_at <= now}면 EXPIRED)과 어긋나 같은 토큰이 JWT 단계는 통과하고 DB 단계는
         * 만료로 읽히는 창을 만든다.
         */
        @Test
        void 만료_정각은_스큐로_살아나지_않는다() {
            String token = provider.createAccessToken(7L, SocialPlatform.KAKAO, UserRole.USER, "f");
            long exp = expOf(token);

            clock.setTo(Instant.ofEpochSecond(exp - 1));
            assertThatCode(() -> provider.parseAccessToken(token)).doesNotThrowAnyException();

            clock.setTo(Instant.ofEpochSecond(exp));
            assertThatThrownBy(() -> provider.parseAccessToken(token))
                    .isInstanceOf(JwtTokenException.class)
                    .extracting(e -> ((JwtTokenException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXPIRED_ACCESS_TOKEN);
        }

        /** 만료된 refresh는 access와 다른 코드로 갈린다 — 클라이언트가 할 일이 다르다. */
        @Test
        void 만료된_refresh는_EXPIRED_REFRESH_TOKEN이다() {
            String token = provider.serializeRefreshToken(
                    provider.newRefreshTokenMaterial(7L, SocialPlatform.KAKAO, "f", "j"));

            clock.setTo(Instant.ofEpochSecond(expOf(token)));

            assertThatThrownBy(() -> provider.parseRefreshToken(token))
                    .isInstanceOf(JwtTokenException.class)
                    .extracting(e -> ((JwtTokenException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXPIRED_REFRESH_TOKEN);
        }

        /** 수명이 0 이하인 토큰은 우리가 만들지 않는다. 만료 검사만으로는 이것이 통과한다. */
        @Test
        void exp가_iat보다_앞서거나_같으면_거절한다() {
            assertInvalid(access(claims -> {
                claims.put("iat", NOW.getEpochSecond());
                claims.put("exp", NOW.getEpochSecond());
            }));
            assertInvalid(access(claims -> {
                claims.put("iat", NOW.getEpochSecond() + 10);
                claims.put("exp", NOW.getEpochSecond() + 5);
            }));
        }

        /** 스큐가 실제로 완화하는 것은 <b>미래 {@code iat}뿐이다</b> — 그 경계도 정확하다. */
        @Test
        void 미래_발급은_스큐_안에서만_받는다() {
            String withinSkew = access(claims -> claims.put("iat", NOW.getEpochSecond() + SKEW_SECONDS));
            String beyondSkew =
                    access(claims -> claims.put("iat", NOW.getEpochSecond() + SKEW_SECONDS + 1));

            assertThatCode(() -> provider.parseAccessToken(withinSkew)).doesNotThrowAnyException();
            assertInvalid(beyondSkew);
        }

        /** 소수·문자열 NumericDate는 우리 계약이 아니다 — 재구성이 원본과 갈린다. */
        @Test
        void 정수가_아닌_NumericDate는_거절한다() {
            assertInvalid(access(claims -> claims.put("iat", (double) NOW.getEpochSecond() + 0.5)));
            assertInvalid(access(claims -> claims.put("exp", String.valueOf(NOW.getEpochSecond() + 60))));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // subject
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void 사용자_ID가_양수가_아니면_거절한다() {
        assertInvalid(access(claims -> claims.put("sub", "0")));
        assertInvalid(access(claims -> claims.put("sub", "-1")));
        assertInvalid(access(claims -> claims.put("sub", "not-a-number")));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void assertInvalid(String token) {
        assertThatThrownBy(() -> provider.parseAccessToken(token))
                .isInstanceOf(JwtTokenException.class);
    }

    /** 우리 프로바이더가 만드는 것과 <b>같은 클레임 집합</b>에서 출발해 한 가지만 비튼다. */
    private String access(Consumer<Map<String, Object>> mutation) {
        Map<String, Object> claims = baseAccessClaims();
        mutation.accept(claims);
        return Jwts.builder().setClaims(claims).signWith(ACCESS_KEY, SignatureAlgorithm.HS512)
                .compact();
    }

    private String refresh(Consumer<Map<String, Object>> mutation) {
        Map<String, Object> claims = baseRefreshClaims();
        mutation.accept(claims);
        return Jwts.builder().setClaims(claims).signWith(REFRESH_KEY, SignatureAlgorithm.HS512)
                .compact();
    }

    private JwtBuilder accessBuilder(Consumer<Map<String, Object>> mutation) {
        Map<String, Object> claims = baseAccessClaims();
        mutation.accept(claims);
        return Jwts.builder().setClaims(claims);
    }

    private Map<String, Object> baseAccessClaims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", AUDIENCE);
        claims.put("sub", "7");
        claims.put("type", "access");
        claims.put("platform", SocialPlatform.KAKAO.name());
        claims.put("role", UserRole.USER.name());
        claims.put("fid", "fam-1");
        claims.put("ver", JwtTokenProvider.TOKEN_FORMAT_VERSION);
        claims.put("iat", clock.instant().getEpochSecond());
        claims.put("exp", clock.instant().getEpochSecond() + 1_800);
        return claims;
    }

    private Map<String, Object> baseRefreshClaims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", AUDIENCE);
        claims.put("sub", "7");
        claims.put("jti", "jti-1");
        claims.put("type", "refresh");
        claims.put("platform", SocialPlatform.KAKAO.name());
        claims.put("fid", "fam-1");
        claims.put("ver", JwtTokenProvider.TOKEN_FORMAT_VERSION);
        claims.put("iat", clock.instant().getEpochSecond());
        claims.put("exp", clock.instant().getEpochSecond() + 3_600);
        return claims;
    }

    private static long expOf(String jwt) {
        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]),
                StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"exp\":(\\d+)").matcher(payload);
        assertThat(matcher.find()).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private JwtProperties properties() {
        JwtProperties properties = new JwtProperties();
        properties.setAccessSecretKey(ACCESS_SECRET);
        properties.setRefreshSecretKey(REFRESH_SECRET);
        properties.setIssuer(ISSUER);
        properties.setAudience(AUDIENCE);
        properties.setClockSkewSeconds(SKEW_SECONDS);
        return properties;
    }

    /** 테스트 전용 키. 설정 파일의 실제 비밀 값을 읽지 않는다. HS512는 64바이트 이상이 필요하다. */
    private static String base64Key(char fill) {
        byte[] raw = new byte[64];
        java.util.Arrays.fill(raw, (byte) fill);
        return Base64.getEncoder().encodeToString(raw);
    }
}
