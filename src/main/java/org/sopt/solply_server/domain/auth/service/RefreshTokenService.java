package org.sopt.solply_server.domain.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.auth.config.AuthProperties;
import org.sopt.solply_server.domain.auth.entity.RefreshTokenRow;
import org.sopt.solply_server.domain.auth.entity.RefreshTokenState;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.repository.RefreshTokenRepository;
import org.sopt.solply_server.domain.auth.repository.RefreshTokenRepository.LockedUser;
import org.sopt.solply_server.domain.user.entity.UserRole;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.jwt.JwtTokenProvider;
import org.sopt.solply_server.global.jwt.dto.RefreshTokenMaterial;
import org.sopt.solply_server.global.jwt.dto.RefreshTokenPayload;
import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * refresh 계열의 발급·회전·폐기. <b>모든 상태 변경이 여기를 지난다.</b>
 *
 * <h2>트랜잭션 경계</h2>
 * 전부 {@code REQUIRES_NEW} + {@code READ_COMMITTED}다. 둘 다 방어적인 선택이다 —
 * 호출자가 이미 트랜잭션을 열고 있으면 스프링은 격리 수준 지정을 <b>조용히 무시하고</b> 그 안에
 * 참여한다. 그러면 회전 판정이 저장소의 기본 격리(REPEATABLE READ)에서 돌아, CAS에 진 요청이
 * 다시 읽은 행에서 <em>자기가 처음 본 값</em>을 보게 된다. 경계를 떼어 그 일이 일어나지 않게 한다.
 *
 * <h2>잠금</h2>
 * 사용자 행을 먼저 잠그고(→ refresh 행) 그 안에서만 쓴다. 외부 OAuth HTTP 호출은 이 잠금 밖에서
 * 이미 끝나 있어야 한다 — 네트워크 지연이 잠금 보유 시간이 되면 같은 사용자의 다른 요청이
 * 그만큼 대기한다.
 *
 * <h2>회전이 두 문장인 이유</h2>
 * 분기용 조회는 잠금 <b>밖</b>에서 한 번, 판정은 잠금 <b>안</b>의 조건부 UPDATE가 한다.
 * 그래서 같은 부모로 동시에 들어온 두 요청은 둘 다 ACTIVE를 보고 진입하고, 잠금을 늦게 얻은
 * 쪽이 실제로 0행을 받아 재조회 경로를 탄다. 잠금 안에서만 읽으면 이 경로는 코드에만 있고
 * 실행되지는 않는 분기가 된다 — 검증할 수 없는 안전장치는 안전장치가 아니다.
 *
 * <h2>재사용 관찰 — 폐기의 출처를 묻지 않는 것이 정책이다</h2>
 * {@code revoked_at}은 <b>누가 찍었는지를 남기지 않는다.</b> 로그아웃이 찍은 도장과 탈퇴가 찍은
 * 도장과 재사용 감지가 찍은 도장이 같은 컬럼이고, {@link #classify}는 그 컬럼만 본다. 그래서
 * <b>이미 폐기된 토큰으로 들어온 재발급은 출처와 무관하게 전체 폐기다.</b> 사유 컬럼이나 로그아웃
 * 예외를 두지 않는 것은 빠뜨린 것이 아니라 고른 것이다 — 폐기된 토큰이 다시 관찰됐다는 사실
 * 자체를 신호로 삼고, 그 신호가 정상 지연에서도 나올 수 있다는 것을 비용으로 받는다.
 *
 * <p>그래서 <b>계열 로그아웃의 "그 계열만"은 로그아웃이라는 동작의 범위이지, 그 뒤에 오는 요청까지
 * 다른 계열이 안전하다는 보장이 아니다.</b> 로그아웃 뒤에 그 계열의 refresh가 한 번이라도 더
 * 도착하면 그 사용자의 모든 계열이 끊긴다. 탈퇴 후 재가입에서 열린 새 계열도 같다 — 옛 계열의
 * 폐기된 토큰이 한 번 도착하면 방금 만든 계열까지 함께 간다.
 *
 * <p><b>"폐기 커밋 뒤의 새 로그인은 허용된다"도 이 면역과 다른 말이다.</b> 그것은 잠금으로
 * 직렬화된 뒤의 <em>발급</em>이 막히지 않는다는 뜻일 뿐, 새로 만든 계열이 <em>미래의 재사용
 * 관찰</em>에서 면제된다는 뜻이 아니다.
 *
 * <p>클라이언트 쪽 계약이 여기서 나온다 — 로그아웃을 부르는 순간 <b>대기 중인 refresh 요청을
 * 중단하고 재시도 큐를 비우고 저장된 토큰을 버려야 한다.</b> 이미 네트워크로 나간 요청을 서버가
 * 회수해 줄 수는 없으므로, 그 한 건이 남기는 전체 폐기는 이 정책이 감수하는 상한이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthProperties authProperties;
    private final Clock clock;

    /** 소셜 로그인. 새 계열을 연다 — 기존 기기의 계열을 끊지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public TokenCollectionDto issue(Long userId, SocialPlatform platform) {
        return issueNewFamily(userId, platform, false);
    }

    /**
     * 어드민 교환. 발급 직전에 <b>잠금 안에서</b> 권한을 다시 본다 — state 발급과 코드 교환
     * 사이에 권한이 내려간 사용자에게 ADMIN 토큰을 내주지 않기 위해서다. 교환 전에 따로 읽어
     * 확인하면 그 확인과 발급 사이가 다시 벌어진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public TokenCollectionDto issueForAdmin(Long userId, SocialPlatform platform) {
        return issueNewFamily(userId, platform, true);
    }

    /**
     * 재발급. 결과는 {@link RotationResult}이며, 재사용 판정은 <b>예외가 아니라 값</b>이다
     * (그 이유는 {@code RotationResult} javadoc).
     *
     * <h3>판정 순서 — 비활성 사용자가 상태 분류보다 먼저다</h3>
     * 사용자 잠금(2단계)이 상태 분류(3·4단계)보다 앞이므로, <b>탈퇴했거나 행이 없는 사용자의
     * refresh는 그 행이 어떤 상태든 {@code AUTH-017}이 된다</b> — 폐기된 토큰이어도
     * {@code AUTH-013}(재사용 감지)이 아니다.
     *
     * <p>이 순서가 재사용 감지를 약화시키지 않는다. 탈퇴는 소프트 삭제와 <b>전체 폐기를 한
     * 트랜잭션에서</b> 커밋하므로({@code UserWithdrawService#withdraw}), 여기까지 내려온
     * 시점에 그 사용자의 계열은 이미 전부 끊겨 있다. 다시 폐기할 것이 없는 상태에서 전체 폐기를
     * 한 번 더 도는 대신, 클라이언트가 알아들을 수 있는 401 하나로 끝낸다.
     *
     * <p>반대로 <b>재가입한 사용자는 여기에 걸리지 않는다.</b>
     * {@code SocialUserService#reactivateIfDeleted}가 같은 user 행을 되살리므로 잠금은 성공하고,
     * 탈퇴 전 계열의 폐기된 행으로 들어온 요청은 4단계에서 재사용으로 읽혀 <b>재가입으로 막
     * 열린 새 계열까지 폐기된다</b>. 그것이 정책이다(클래스 javadoc의 "재사용 관찰" 항목).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public RotationResult rotate(RefreshTokenPayload payload) {
        // 1) 분기용 조회 — 잠금 밖이다. 여기서 본 상태는 참고일 뿐 판정이 아니다.
        RefreshTokenRow initial = loadAndVerify(payload);
        boolean activeCandidate = initial.state(clock.instant()) == RefreshTokenState.ACTIVE;

        // 2) 사용자 잠금. 이 뒤로는 같은 사용자의 다른 발급·회전·폐기가 끼어들지 못한다.
        LockedUser user = requireActiveUserForRotation(payload.userId());

        // 3) ACTIVE 후보였으면 조건부 UPDATE가 승패를 정한다.
        if (activeCandidate) {
            Instant now = clock.instant();
            long graceExpiresAt = now.plus(authProperties.getRotationGrace()).toEpochMilli();
            int rotated = refreshTokenRepository.markRotated(
                    initial.id(), now.toEpochMilli(), graceExpiresAt, now.getEpochSecond());
            if (rotated == 1) {
                return new RotationResult.Rotated(issueChild(initial, user));
            }
            log.info("refresh 회전 CAS 실패 — 최신 상태로 재분류한다. userId={}, jti={}",
                    payload.userId(), payload.jwtId());
        }

        // 4) CAS에 졌거나 애초에 ACTIVE가 아니었다. 어느 쪽이든 잠금 안에서 최신 행을 다시 읽는다.
        return classify(loadAndVerify(payload), user);
    }

    /**
     * 계열 하나를 끊는다(로그아웃). 이미 끊긴 계열을 다시 끊어도 예외가 아니다 — 같은 access로
     * 반복 호출하는 클라이언트가 정상이기 때문이다.
     *
     * <p>탈퇴한 사용자도 막지 않는다. 탈퇴가 이미 전부 폐기했으므로 이 호출은 0행을 바꾸고 끝나며,
     * 여기서 404를 내면 "로그아웃은 몇 번을 해도 성공한다"는 계약이 깨진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public int revokeFamily(Long userId, String familyId) {
        lockUser(userId);
        int revoked = refreshTokenRepository.revokeFamily(userId, familyId, clock.millis());
        log.info("계열 로그아웃 - userId={}, familyId={}, revoked={}", userId, familyId, revoked);
        return revoked;
    }

    private TokenCollectionDto issueNewFamily(Long userId, SocialPlatform platform, boolean requireAdmin) {
        LockedUser user = requireActiveUser(userId);
        UserRole role = roleOf(user);
        if (requireAdmin && role != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.NOT_ADMIN_USER);
        }

        String familyId = UUID.randomUUID().toString();
        RefreshTokenMaterial material = jwtTokenProvider.newRefreshTokenMaterial(
                userId, platform, familyId, UUID.randomUUID().toString());
        refreshTokenRepository.insert(material, null);

        return TokenCollectionDto.of(
                jwtTokenProvider.createAccessToken(userId, platform, role, familyId),
                jwtTokenProvider.serializeRefreshToken(material)
        );
    }

    /**
     * 부모를 회전시킨 직후의 자식 생성. <b>부모 UPDATE와 같은 트랜잭션이다</b> —
     * 여기서 실패하면 부모의 회전 표시도 함께 없던 일이 된다. 그러지 않으면 부모는 죽었는데
     * 자식은 없는 계열, 곧 사용자가 재로그인 외에는 빠져나올 수 없는 상태가 남는다.
     */
    private TokenCollectionDto issueChild(RefreshTokenRow parent, LockedUser user) {
        RefreshTokenMaterial material = jwtTokenProvider.newRefreshTokenMaterial(
                parent.userId(), parent.platform(), parent.familyId(), UUID.randomUUID().toString());
        refreshTokenRepository.insert(material, parent.jwtId());

        return TokenCollectionDto.of(
                jwtTokenProvider.createAccessToken(
                        parent.userId(), parent.platform(), roleOf(user), parent.familyId()),
                jwtTokenProvider.serializeRefreshToken(material)
        );
    }

    private RotationResult classify(RefreshTokenRow row, LockedUser user) {
        Instant now = clock.instant();
        return switch (row.state(now)) {
            // CAS가 0행을 냈는데 행은 여전히 살아 있다 — 시계가 거꾸로 가지 않는 한 불가능하다.
            case ACTIVE -> throw inconsistent(row, "조건부 UPDATE가 실패했는데 상태가 ACTIVE다");

            case GRACE -> new RotationResult.Rotated(reissueWithinGrace(row, user, now));

            // JWT는 아직 살아 있는데 DB 만료가 지났다(설정 변경 등). 재사용이 아니다.
            case EXPIRED -> throw new JwtTokenException(ErrorCode.EXPIRED_REFRESH_TOKEN);

            // 유예가 끝난 회전 토큰과 이미 폐기된 토큰은 같은 정책이다 — 사용자 전체 폐기.
            case REVOKED, GRACE_ENDED -> revokeEverything(row);
        };
    }

    /**
     * 유예 재구성. <b>쓰기가 하나도 없다.</b> 부모의 유예도 자식의 만료도 연장하지 않는다 —
     * 연장하면 옛 토큰 하나로 계열을 무한히 살려 둘 수 있다.
     *
     * <p>refresh는 자식과 <b>똑같은 문자열</b>이고, access만 새로 발급한다. 응답 유실로 다시 온
     * 요청이 받아야 할 것은 "직전에 못 받은 그 토큰"이지 또 다른 새 토큰이 아니다.
     */
    private TokenCollectionDto reissueWithinGrace(RefreshTokenRow parent, LockedUser user, Instant now) {
        RefreshTokenRow child = refreshTokenRepository.findByParentJwtId(parent.jwtId())
                .orElseThrow(() -> inconsistent(parent, "유예 중인 부모에게 자식이 없다"));

        if (child.userId() != parent.userId() || !child.familyId().equals(parent.familyId())) {
            throw inconsistent(child, "자식의 소유자 또는 계열이 부모와 다르다");
        }
        if (!child.isConsistent()) {
            throw inconsistent(child, "자식의 회전 시각과 유예 종료 시각 중 한쪽만 채워져 있다");
        }
        // 형식이 바뀐 뒤에 남은 옛 행은 같은 문자열로 되살릴 수 없다. 그것은 정합성 오류가 아니라
        // 전환의 대가이므로, 재사용이 아니라 "대체됨"으로 돌려보낸다.
        //
        // 이 버전 비교는 "옛 토큰을 거절하는 자리"가 아니다 — 옛 ver를 실은 토큰은 파싱 단계의
        // JwtTokenProvider#validateFormatVersion이 INVALID_TOKEN으로 먼저 끊는다. 여기는 토큰이
        // 주장한 판과 DB 행에 적힌 판이 갈리는 경우를 위한 별도 경로이고, 정상적으로는 부모가
        // 지금 판이면 자식도 지금 판이라 실행되지 않는다. 실행됐다면 그것 자체가 신호다.
        if (child.tokenFormatVersion() != JwtTokenProvider.TOKEN_FORMAT_VERSION
                || child.state(now) != RefreshTokenState.ACTIVE) {
            throw new JwtTokenException(ErrorCode.REFRESH_TOKEN_SUPERSEDED);
        }

        return TokenCollectionDto.of(
                jwtTokenProvider.createAccessToken(
                        child.userId(), child.platform(), roleOf(user), child.familyId()),
                jwtTokenProvider.serializeRefreshToken(child.toMaterial())
        );
    }

    private RotationResult revokeEverything(RefreshTokenRow row) {
        Instant now = clock.instant();
        int revoked = refreshTokenRepository.revokeAllByUserId(
                row.userId(), now.toEpochMilli(), now.getEpochSecond());
        log.warn("refresh 재사용 감지 — 사용자 전체 폐기. userId={}, familyId={}, jti={}, revoked={}",
                row.userId(), row.familyId(), row.jwtId(), revoked);
        return new RotationResult.ReuseDetected(revoked);
    }

    /**
     * 행을 읽고 토큰이 주장한 고정값과 대조한다.
     *
     * <p>발급 시각·만료 시각까지 맞춰 보는 이유는 <b>재구성이 그 값들 위에 서 있기 때문이다.</b>
     * 하나라도 어긋나면 같은 문자열을 만들 수 없고, 어긋났다는 것 자체가 우리 서명 키로 만들어진
     * 토큰과 우리 DB가 갈렸다는 뜻이다.
     */
    private RefreshTokenRow loadAndVerify(RefreshTokenPayload payload) {
        RefreshTokenRow row = refreshTokenRepository.findByJwtId(payload.jwtId())
                .orElseThrow(() -> new JwtTokenException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));

        boolean matches = row.userId() == payload.userId()
                && row.familyId().equals(payload.familyId())
                && row.platform() == payload.platform()
                && row.tokenFormatVersion() == payload.formatVersion()
                && row.issuedAtEpochSecond() == payload.issuedAtEpochSecond()
                && row.expiresAtEpochSecond() == payload.expiresAtEpochSecond();
        if (!matches) {
            throw inconsistent(row, "토큰의 고정 클레임이 저장된 값과 다르다");
        }
        // 상태 판정({@code state})이 유예 종료 시각을 읽으므로 정합성 검사가 그보다 먼저여야 한다.
        // 여기서 걸러 두면 이 뒤의 모든 분기가 "있을 수 있는 조합"만 다룬다.
        if (!row.isConsistent()) {
            throw inconsistent(row, "회전 시각과 유예 종료 시각 중 한쪽만 채워져 있다");
        }
        return row;
    }

    /**
     * 발급 경로(소셜 로그인·어드민 교환)의 사용자 확인. 없거나 탈퇴한 사용자는 <b>404
     * {@code USER-001}</b>이다 — 방금 외부 인증을 통과한 주체를 우리 쪽에서 찾지 못했다는 말이라
     * "사용자를 찾을 수 없다"가 그대로 맞는 서술이다.
     */
    private LockedUser requireActiveUser(Long userId) {
        LockedUser user = lockUser(userId);
        if (user.deleted()) {
            throw new EntityNotFoundException(ErrorCode.NOT_FOUND_USER);
        }
        return user;
    }

    /**
     * 재발급 경로의 사용자 확인. <b>여기만 401 {@code AUTH-017}이다.</b>
     *
     * <p>발급 경로와 코드를 가르는 이유는 클라이언트가 읽는 뜻이 다르기 때문이다. 재발급에
     * 실패한 클라이언트가 해야 할 일은 "저장된 토큰을 버리고 로그인 화면으로"이고, 그 분기는
     * 보통 401에 걸려 있다. 여기서 404를 내보내면 그 규칙에 걸리지 않아 <b>죽은 토큰으로 재시도를
     * 반복한다</b>. 탈퇴한 사용자의 모든 재발급 시도가 이 길을 지나므로 드문 경로가 아니다.
     *
     * <p>행이 아예 없는 경우도 같은 코드다. 서명이 맞는 refresh를 들고 왔는데 그 주인이 없다는
     * 것은 자원 조회 실패가 아니라 자격이 끝났다는 뜻이고, 구분해서 알려 줄 이유도 없다.
     */
    private LockedUser requireActiveUserForRotation(Long userId) {
        LockedUser user = refreshTokenRepository.lockUser(userId)
                .orElseThrow(() -> new JwtTokenException(ErrorCode.REFRESH_TOKEN_USER_INACTIVE));
        if (user.deleted()) {
            throw new JwtTokenException(ErrorCode.REFRESH_TOKEN_USER_INACTIVE);
        }
        return user;
    }

    private LockedUser lockUser(Long userId) {
        return refreshTokenRepository.lockUser(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));
    }

    private UserRole roleOf(LockedUser user) {
        try {
            return UserRole.valueOf(user.role());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * {@code JwtTokenException}이 아니라 {@code BusinessException}인 것이 분류다 — 이것은
     * 토큰이 잘못됐다는 말이 아니라 서버 상태가 깨졌다는 말이고, 그래서 401이 아니라 500이다.
     */
    private BusinessException inconsistent(RefreshTokenRow row, String reason) {
        log.error("refresh 정합성 오류 - {} (id={}, userId={}, familyId={}, jti={})",
                reason, row.id(), row.userId(), row.familyId(), row.jwtId());
        return new BusinessException(ErrorCode.REFRESH_TOKEN_STATE_INCONSISTENT);
    }
}
