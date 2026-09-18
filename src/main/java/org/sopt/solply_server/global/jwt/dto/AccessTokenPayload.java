package org.sopt.solply_server.global.jwt.dto;

import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.UserRole;

/**
 * 검증을 통과한 access 토큰이 실어 온 값. <b>여기 담긴 것만으로 인증이 끝난다</b> — 필터는
 * 이 뒤로 DB를 읽지 않는다.
 *
 * <p>네 값이 모두 non-null인 것이 계약이다. 하나라도 없는 토큰은 파싱 단계에서 거절되므로
 * (필수 클레임), 이 레코드를 받은 코드는 null 분기를 갖지 않는다.
 */
public record AccessTokenPayload(
        Long userId,
        SocialPlatform platform,
        UserRole role,
        String familyId
) {
}
