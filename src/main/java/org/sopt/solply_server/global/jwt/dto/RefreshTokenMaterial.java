package org.sopt.solply_server.global.jwt.dto;

import org.sopt.solply_server.domain.auth.entity.SocialPlatform;

/**
 * refresh 토큰 문자열을 만드는 데 필요한 값 전부.
 *
 * <p><b>이 레코드가 곧 "원문을 저장하지 않는다"의 근거다.</b> 같은 값으로 같은 빌더를 통과시키면
 * 같은 바이트가 나오므로(HMAC은 결정적이다), 유예 중에 돌려줄 문자열을 보관할 필요가 없다.
 * 그래서 여기 담긴 값과 {@code refresh_token} 테이블의 컬럼은 일대일이어야 한다 —
 * 한쪽에만 있는 값이 생기는 순간 재구성이 원본과 갈린다.
 *
 * <p>시각이 밀리초가 아니라 <b>정수 초</b>인 것도 같은 이유다. JWT의 NumericDate가 초라서
 * 밀리초를 들고 있으면 직렬화 과정에서 잘려 나가고, DB에 밀리초로 저장하면 왕복 후 다른 초로
 * 반올림될 여지가 생긴다.
 */
public record RefreshTokenMaterial(
        Long userId,
        SocialPlatform platform,
        String familyId,
        String jwtId,
        int formatVersion,
        long issuedAtEpochSecond,
        long expiresAtEpochSecond
) {
}
