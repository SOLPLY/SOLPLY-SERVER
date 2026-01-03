package org.sopt.solply_server.domain.recommend.cache;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface DailyRecommendCache {
    List<Long> getTodayPlaceIds(Long userId, Long townId, LocalDate date);
    void saveTodayRecommendedPlaceIds(Long userId, Long townId, LocalDate date, List<Long> placeIds);
    Set<Long> getCooldownPlaceIds(Long userId, Long townId, LocalDate date, int days); // 최근 n일 추천된 placeIds 합집합
}
