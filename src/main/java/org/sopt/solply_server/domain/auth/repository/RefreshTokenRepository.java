package org.sopt.solply_server.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.concurrent.TimeUnit;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {

    private final RedisTemplate<String, String> redisTemplate;

    public void save(Long memberId, String refreshToken, long expirationTime) {
        redisTemplate.opsForValue().set(
                String.valueOf(memberId),
                refreshToken,
                expirationTime,
                TimeUnit.MILLISECONDS
        );
    }

    public String findByMemberId(Long memberId) {
        return redisTemplate.opsForValue().get(String.valueOf(memberId));
    }

    public void deleteByMemberId(Long memberId) {
        redisTemplate.delete(String.valueOf(memberId));
    }
}