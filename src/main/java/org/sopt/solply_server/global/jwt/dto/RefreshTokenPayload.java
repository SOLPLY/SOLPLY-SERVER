package org.sopt.solply_server.global.jwt.dto;

import org.sopt.solply_server.domain.auth.entity.SocialPlatform;

/**
 * 검증을 통과한 refresh 토큰이 실어 온 값.
 *
 * <p>이 값들은 <b>주장</b>이지 사실이 아니다. 서명이 맞다는 것과 이 토큰이 지금 살아 있다는 것은
 * 다른 문제이고, 후자는 {@code refresh_token} 행이 정한다. 그래서 회전 로직은 여기 담긴
 * {@code jwtId}로 행을 찾은 뒤 <b>소유자·계열·형식 버전이 행과 일치하는지</b> 다시 대조한다 —
 * 어긋나면 정합성 오류다.
 */
public record RefreshTokenPayload(
        Long userId,
        SocialPlatform platform,
        String familyId,
        String jwtId,
        int formatVersion,
        long issuedAtEpochSecond,
        long expiresAtEpochSecond
) {
}
