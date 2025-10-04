package org.sopt.solply_server.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.concurrent.TimeUnit;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${jwt.refresh-token-expire-time}")
    private long refreshTokenExpireTime;

    public void save(Long userId, String refreshToken) {
        redisTemplate.opsForValue().set(
                String.valueOf(userId),
                refreshToken,
                refreshTokenExpireTime,
                TimeUnit.MILLISECONDS
        );
    }

    public String findByUserId(Long userId) {
        return redisTemplate.opsForValue().get(String.valueOf(userId));
    }

    public void deleteByUserId(Long userId) {
        redisTemplate.delete(String.valueOf(userId));
    }
}