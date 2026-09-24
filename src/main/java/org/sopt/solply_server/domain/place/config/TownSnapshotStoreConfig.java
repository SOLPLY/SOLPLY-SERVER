package org.sopt.solply_server.domain.place.config;

import org.sopt.solply_server.domain.place.cache.town.RedisTownSnapshotStore;
import org.sopt.solply_server.domain.place.cache.town.TownPayloadCodec;
import org.sopt.solply_server.domain.place.cache.town.TownSnapshotStore;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 동네 공유 사본 저장소를 고른다. {@code solply.place-list-town-cache.redis.enabled}가 거짓이면
 * 아무 I/O 없이 "없음"으로만 답하는 저장소가 선다.
 *
 * <p>접속 대상은 기존 {@code spring.data.redis.*}와 같고, 연결과 timeout만 따로 둔다.
 */
@Configuration
public class TownSnapshotStoreConfig {

    @Bean
    public TownPayloadCodec townPayloadCodec() {
        return new TownPayloadCodec();
    }

    @Bean
    public TownSnapshotStore townSnapshotStore(PlaceListTownCacheProperties properties,
            TownPayloadCodec codec, PlaceListMeters meters,
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${app.env-prefix:local}") String envPrefix) {
        if (!properties.getRedis().isEnabled()) {
            return TownSnapshotStore.disabled();
        }
        return new RedisTownSnapshotStore(host, port, password, envPrefix,
                properties.getRedis(), codec, meters);
    }
}
