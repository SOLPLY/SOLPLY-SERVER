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

    public void save(String state) {
        try {
            redisTemplate.opsForValue().set(
                    KEY_PREFIX + state,
                    "valid",
                    TTL_MINUTES,
                    TimeUnit.MINUTES
            );
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    /**
     * 존재 여부 확인 후 즉시 삭제 (일회용 보장, GETDEL로 원자적 처리)
     */
    public boolean validateAndConsume(String state) {
        try {
            return redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + state) != null;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }
}
