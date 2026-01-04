package org.sopt.solply_server.domain.recommend.cache;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.global.cache.CacheService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisDailyRecommendCache implements DailyRecommendCache {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String KEY_PREFIX = "recommend:place";

    private final CacheService cacheService;

    @Override
    public List<Long> getTodayPlaceIds(final Long userId, final Long townId, final LocalDate date) {
        if (userId == null || townId == null || date == null) return List.of();

        String key = key(userId, townId, date);
        List<Long> cached = cacheService.getList(key, Long.class);
        return cached == null ? List.of() : cached;
    }

    @Override
    public void saveTodayRecommendedPlaceIds(final Long userId, final Long townId, final LocalDate date,
            final List<Long> placeIds, final int retentionDays) {
        if (userId == null || townId == null || date == null) return;
        if (placeIds == null || placeIds.isEmpty()) return;

        // null 제거 + 중복 제거(순서 유지)
        List<Long> normalized = placeIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (normalized.isEmpty()) return;

        String key = key(userId, townId, date);

        long ttlSeconds = ttlSecondsUntilExpireDate(date, retentionDays);
        if (ttlSeconds <= 0) {
            cacheService.setList(key, placeIds);
            return;
        }

        cacheService.setList(key, placeIds, (int) ttlSeconds, TimeUnit.SECONDS);
    }

    @Override
    public Set<Long> getCooldownPlaceIds(final Long userId, final Long townId, final LocalDate date, final int cooldownDays) {
        if (userId == null || townId == null || date == null) return Set.of();
        if (cooldownDays <= 0) return Set.of();

        // 오늘 제외: date-1 ~ date-cooldownDays
        Set<Long> result = new HashSet<>();
        for (int i = 1; i <= cooldownDays; i++) {
            LocalDate d = date.minusDays(i);
            String key = key(userId, townId, d);

            List<Long> ids = cacheService.getList(key, Long.class);
            if (ids == null || ids.isEmpty()) continue;

            result.addAll(ids);
        }
        return result;
    }

    // ---------------- helpers ----------------

    private String key(final Long userId, final Long townId, final LocalDate date) {
        return KEY_PREFIX + ":" + userId + ":" + townId + ":" + yyyymmdd(date);
    }

    private String yyyymmdd(final LocalDate date) {
        return date.toString().replace("-", "");
    }

    /**
     * "오늘 자정(3일 후 00:00)"까지 남은 TTL(초)
     * - date 파라미터와 무관하게 '지금 기준 오늘'로 계산 (RecommendService도 LocalDate.now() 쓰는 전제)
     */
    private long ttlSecondsUntilExpireDate(LocalDate date, int days) {
        ZonedDateTime now = ZonedDateTime.now(KST);
        ZonedDateTime expireAt =
                date.plusDays(days).plusDays(1).atStartOfDay(KST);
        return Duration.between(now, expireAt).getSeconds();
    }
}