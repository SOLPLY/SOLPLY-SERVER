package org.sopt.solply_server.domain.admin.auth.repository;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AdminOAuthStateRepository {

    private static final String KEY_PREFIX = "admin:oauth:state:";
    private static final long TTL_MINUTES = 10;

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * state → nonce 매핑 저장. nonce는 요청을 시작한 클라이언트를 식별하는 HttpOnly 쿠키 값.
     */
    public void save(String state, String nonce) {
        try {
            redisTemplate.opsForValue().set(
                    KEY_PREFIX + state,
                    nonce,
                    TTL_MINUTES,
                    TimeUnit.MINUTES
            );
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    /**
     * state에 저장된 nonce를 원자적으로 조회·삭제 후, 전달받은 nonce와 일치하는지 검증.
     * nonce 불일치 또는 state 미존재 시 false 반환.
     */
    public boolean validateAndConsume(String state, String nonce) {
        try {
            String stored = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + state);
            return stored != null && stored.equals(nonce);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }
}
