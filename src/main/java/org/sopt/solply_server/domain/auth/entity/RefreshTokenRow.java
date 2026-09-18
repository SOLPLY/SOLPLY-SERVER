package org.sopt.solply_server.domain.auth.entity;

import java.time.Instant;
import org.sopt.solply_server.global.jwt.dto.RefreshTokenMaterial;

/**
 * {@code refresh_token} 한 행. JPA 엔티티가 아니다.
 *
 * <p><b>영속성 컨텍스트를 쓰지 않는 것이 의도다.</b> 회전의 승패는 조건부 UPDATE가 정하고,
 * 진 쪽은 그 직후 <b>최신 행</b>을 다시 읽어야 한다. 1차 캐시가 끼면 방금 남이 바꾼 값 대신
 * 내가 처음 읽은 값이 돌아오고, 그 값으로 재분류하면 멀쩡한 경쟁이 재사용으로 읽힌다.
 * 캐시를 비우는 규율에 기대는 대신 캐시가 없는 길을 쓴다.
 */
public record RefreshTokenRow(
        long id,
        long userId,
        String familyId,
        String jwtId,
        String parentJwtId,
        SocialPlatform platform,
        int tokenFormatVersion,
        long issuedAtEpochSecond,
        long expiresAtEpochSecond,
        Long rotatedAtEpochMilli,
        Long graceExpiresAtEpochMilli,
        Long revokedAtEpochMilli
) {

    public RefreshTokenState state(Instant now) {
        if (revokedAtEpochMilli != null) {
            return RefreshTokenState.REVOKED;
        }
        if (expiresAtEpochSecond <= now.getEpochSecond()) {
            return RefreshTokenState.EXPIRED;
        }
        if (rotatedAtEpochMilli == null) {
            return RefreshTokenState.ACTIVE;
        }
        return now.toEpochMilli() < graceExpiresAtEpochMilli
                ? RefreshTokenState.GRACE
                : RefreshTokenState.GRACE_ENDED;
    }

    /**
     * 회전 시각과 유예 종료 시각은 한 문장에서 함께 쓰인다. 한쪽만 채워진 행은 있을 수 없고,
     * 그런 행을 만나면 <b>재사용으로 단정하지 않는다</b> — 그것은 공격의 증거가 아니라 우리
     * 코드나 데이터가 깨졌다는 증거다.
     */
    public boolean isConsistent() {
        return (rotatedAtEpochMilli == null) == (graceExpiresAtEpochMilli == null);
    }

    /** 이 행이 저장하고 있는, 토큰 문자열을 되살리는 데 필요한 값 전부. */
    public RefreshTokenMaterial toMaterial() {
        return new RefreshTokenMaterial(
                userId, platform, familyId, jwtId,
                tokenFormatVersion, issuedAtEpochSecond, expiresAtEpochSecond);
    }
}
