package org.sopt.solply_server.domain.place.cache.town;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.config.PlaceListTownCacheProperties;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 동네 공유 사본의 Redis 구현.
 *
 * <p><b>연결은 이 저장소 전용이다.</b> 접속 대상은 기존 Redis 설정을 그대로 쓰되, 인증·토큰이
 * 쓰는 연결과 timeout을 나누지 않으려고 커넥션 팩토리를 따로 둔다. 빈으로 노출하지 않는다 —
 * {@code RedisConnectionFactory} 타입 빈이 둘이 되면 기존 주입이 모호해진다.
 *
 * <p><b>timeout은 짧고 유한하다</b>(기본 connect·command 200ms). 목록 요청 예산 1초 안에서 Redis가
 * 응답하지 않아도 DB 폴백을 할 여유를 남기기 위해서다. 연결이 끊긴 동안 명령은 줄 세우지 않고
 * 바로 거절한다.
 */
@Slf4j
public class RedisTownSnapshotStore implements TownSnapshotStore, DisposableBean {

    private final LettuceConnectionFactory connectionFactory;
    private final RedisTemplate<String, byte[]> redis;
    private final TownPayloadCodec codec;
    private final PlaceListMeters meters;
    private final String keyPrefix;
    private final Duration payloadTtl;

    public RedisTownSnapshotStore(String host, int port, String password, String envPrefix,
            PlaceListTownCacheProperties.Redis properties, TownPayloadCodec codec,
            PlaceListMeters meters) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isEmpty()) {
            server.setPassword(password);
        }
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(properties.getCommandTimeout())
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(properties.getConnectTimeout())
                                .build())
                        .timeoutOptions(TimeoutOptions.enabled(properties.getCommandTimeout()))
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .build())
                .build();
        this.connectionFactory = new LettuceConnectionFactory(server, client);
        this.connectionFactory.afterPropertiesSet();
        this.connectionFactory.start();

        this.redis = new RedisTemplate<>();
        this.redis.setConnectionFactory(connectionFactory);
        this.redis.setKeySerializer(new StringRedisSerializer());
        this.redis.setValueSerializer(RedisSerializer.byteArray());
        this.redis.afterPropertiesSet();

        this.codec = codec;
        this.meters = meters;
        this.keyPrefix = envPrefix + ":place-list:town:s" + TownPayloadCodec.SCHEMA + ":";
        this.payloadTtl = properties.getPayloadTtl();
    }

    @Override
    public boolean enabled() {
        return true;
    }

    /** 환경 접두어 + 스키마 + 동네 + 번호. 스키마가 바뀌면 키 공간도 갈린다. */
    public String keyOf(TownCacheKey key) {
        return keyPrefix + key.townId() + ":v" + key.version();
    }

    @Override
    public Fetch fetch(TownCacheKey key) {
        byte[] bytes;
        try {
            bytes = redis.opsForValue().get(keyOf(key));
        } catch (RuntimeException e) {
            log.warn("동네 공유 사본 조회 실패 - key={}: {}", key, e.toString());
            meters.redisLookup(PlaceListMeters.RedisLookup.UNAVAILABLE);
            return new Fetch.Unavailable("redis: " + e.getClass().getSimpleName());
        }
        if (bytes == null) {
            meters.redisLookup(PlaceListMeters.RedisLookup.MISS);
            return MISS;
        }
        try {
            TownPlaces places = codec.decode(key, bytes);
            meters.redisLookup(PlaceListMeters.RedisLookup.HIT);
            return new Fetch.Hit(places);
        } catch (TownPayloadCodec.InvalidPayloadException e) {
            // 깨진 사본은 "없음"이 아니다 — 지우지도, 덮지도 않는다. 보관 기간이 끝나면 사라진다
            log.warn("동네 공유 사본이 올바르지 않다 - key={}: {}", key, e.getMessage());
            meters.redisLookup(PlaceListMeters.RedisLookup.UNAVAILABLE);
            return new Fetch.Unavailable("corrupt");
        }
    }

    @Override
    public boolean putIfAbsent(TownCacheKey key, byte[] payload) {
        // SET key value NX PX ttl — 있으면 내용도 보관 기간도 그대로다
        Boolean stored = redis.opsForValue().setIfAbsent(keyOf(key), payload, payloadTtl);
        return Boolean.TRUE.equals(stored);
    }

    @Override
    public void destroy() {
        connectionFactory.destroy();
    }
}
