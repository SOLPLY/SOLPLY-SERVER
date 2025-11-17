package org.sopt.solply_server.domain.place.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.dto.PlaceBookmarkRedisDto;
import org.sopt.solply_server.domain.place.repository.PlaceBookmarkRepository;
import org.sopt.solply_server.domain.place.service.cache.PlaceBookmarkRedisDataManager;
import org.sopt.solply_server.domain.user.service.mypage.PlaceBookmarkQueryService;
import org.sopt.solply_server.global.cache.RedisKeyGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceBookmarkQueryServiceImpl implements PlaceBookmarkQueryService {

    private final PlaceBookmarkRedisDataManager placeBookmarkRedisDataManager;
    private final PlaceBookmarkRepository placeBookmarkRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Boolean> getBookmarkStatus(Long userId, List<Long> placeIds) {

        Map<Long, Boolean> result = new HashMap<>();
        if (placeIds.isEmpty()) {
            return result;
        }

        // TODO: 성능 최적화 필요 (bulk 조회)
        for (Long placeId : placeIds) {
            String bookmarkKey = RedisKeyGenerator.generatePlaceBookmarkKey(userId, placeId);
            PlaceBookmarkRedisDto bookmarkData = placeBookmarkRedisDataManager.getBookmarkDto(bookmarkKey);

            boolean isBookmarked;
            if (bookmarkData != null) {
                isBookmarked = bookmarkData.isActive();
            } else {
                isBookmarked = placeBookmarkRepository.existsByPlaceIdAndUserId(placeId, userId);
            }
            result.put(placeId, isBookmarked);
        }

        return result;
    }

}