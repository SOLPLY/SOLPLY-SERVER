package org.sopt.solply_server.domain.admin.auth.repository;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AdminAuthCodeRepository {

    private static final String KEY_PREFIX = "admin:auth:code:";
    private static final long TTL_MINUTES = 5;

    private final RedisTemplate<String, String> redisTemplate;

    public void save(String authCode, Long userId, SocialPlatform platform) {
        try {
            redisTemplate.opsForValue().set(
                    KEY_PREFIX + authCode,
                    userId + ":" + platform.name(),
                    TTL_MINUTES,
                    TimeUnit.MINUTES
            );
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    /**
     * 조회 후 즉시 삭제 (일회용 보장, GETDEL로 원자적 처리)
     */
    public String pop(String authCode) {
        try {
            return redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + authCode);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }
}
