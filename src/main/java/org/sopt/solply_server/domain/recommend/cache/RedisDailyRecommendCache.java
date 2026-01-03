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
    public List<Long> getTodayPlaceIds(Long userId, Long townId, LocalDate date) {
        if (userId == null || townId == null || date == null) return List.of();

        String key = key(userId, townId, date);
        List<Long> cached = cacheService.getList(key, Long.class);
        return cached == null ? List.of() : cached;
    }

    @Override
    public void saveTodayRecommendedPlaceIds(Long userId, Long townId, LocalDate date, List<Long> placeIds) {
        if (userId == null || townId == null || date == null) return;
        if (placeIds == null || placeIds.isEmpty()) return;

        // null 제거 + 중복 제거(순서 유지)
        List<Long> normalized = placeIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (normalized.isEmpty()) return;

        String key = key(userId, townId, date);

        long ttlSeconds = ttlSecondsUntilEndOfDay();
        if (ttlSeconds <= 0) {
            // 혹시나 자정이 지났거나 계산이 꼬였을 때: 그냥 저장(또는 저장하지 않기)
            cacheService.setList(key, normalized);
            return;
        }

        cacheService.setList(key, normalized, (int) ttlSeconds, TimeUnit.SECONDS);
    }

    @Override
    public Set<Long> getCooldownPlaceIds(Long userId, Long townId, LocalDate date, int days) {
        if (userId == null || townId == null || date == null) return Set.of();
        if (days <= 0) return Set.of();

        // 오늘 제외: date-1 ~ date-days
        Set<Long> result = new HashSet<>();
        for (int i = 1; i <= days; i++) {
            LocalDate d = date.minusDays(i);
            String key = key(userId, townId, d);

            List<Long> ids = cacheService.getList(key, Long.class);
            if (ids == null || ids.isEmpty()) continue;

            result.addAll(ids);
        }
        return result;
    }

    // ---------------- helpers ----------------

    private String key(Long userId, Long townId, LocalDate date) {
        return KEY_PREFIX + ":" + userId + ":" + townId + ":" + yyyymmdd(date);
    }

    private String yyyymmdd(LocalDate date) {
        return date.toString().replace("-", "");
    }

    /**
     * "오늘 자정(다음날 00:00)"까지 남은 TTL(초)
     * - date 파라미터와 무관하게 '지금 기준 오늘'로 계산 (RecommendService도 LocalDate.now() 쓰는 전제)
     */
    private long ttlSecondsUntilEndOfDay() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        ZonedDateTime endExclusive = now.toLocalDate().plusDays(1).atStartOfDay(KST);
        return Duration.between(now, endExclusive).getSeconds();
    }
}