package org.sopt.solply_server.domain.place.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.dto.BookmarkRedisDto;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceBookmark;
import org.sopt.solply_server.domain.place.repository.PlaceBookmarkRepository;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.cache.CacheService;
import org.sopt.solply_server.global.cache.RedisKeyGenerator;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceBookmarkService {

    private final UserRepository userRepository;
    private final PlaceBookmarkRepository placeBookmarkRepository;
    private final PlaceRepository placeRepository;
    private final CacheService cacheService;

    /**
     * 북마크 생성
     */
    @Transactional
    public void createPlaceBookmark(final Long userId, final Long placeId) {
        // 사용자 및 장소 검증
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));
        Place place = placeRepository.findById(placeId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE));

        String bookmarkKey = RedisKeyGenerator.generatePlaceBookmarkKey(userId, placeId);

        try {
            // 북마크 DTO 생성 (Record의 정적 팩토리 메서드 사용)
            BookmarkRedisDto bookmarkData = BookmarkRedisDto.createActive(userId, placeId);

            // 개별 북마크 정보 저장 (TTL 1시간)
            cacheService.set(bookmarkKey, bookmarkData);

            log.info("북마크 Redis 저장 완료 - userId: {}, placeId: {}", userId, placeId);

        } catch (Exception e) {
            log.error("북마크 Redis 저장 실패 - userId: {}, placeId: {}", userId, placeId, e);
            PlaceBookmark bookmark = PlaceBookmark.create(place, user);
            saveToDatabase(bookmark);
            log.debug("북마크 DB 저장 완료 - userId: {}, placeId: {}", user.getId(), place.getId());
        }
    }

    /**
     * 북마크 삭제(단일)
     */
    @Transactional
    public void deletePlaceBookmark(final Long userId, final Long placeId) {
        String bookmarkKey = RedisKeyGenerator.generatePlaceBookmarkKey(userId, placeId);

        BookmarkRedisDto deleteMarker = BookmarkRedisDto.createDeleted(userId, placeId);
        cacheService.set(bookmarkKey, deleteMarker);

        log.info("장소 북마크 삭제 마커 설정 완료 - userId: {}, placeId: {}", userId, placeId);
    }

    /**
     * 북마크 배치 삭제
     * - DB에서 북마크 삭제
     * - Redis에서도 플러쉬되지 않도록 삭제 마커 부여
     */
    @Transactional
    public void deletePlaceBookmarks(final Long userId, final List<Long> placeIds) {
        for (Long placeId : placeIds) {
            deletePlaceBookmark(userId, placeId);
        }
    }

    /**
     * 여러 장소의 북마크 상태를 조회
     */
    public Map<Long, Boolean> getPlaceBookmarkMap(List<Long> placeIds, Long userId) {
        if (placeIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Boolean> bookmarkMap = new HashMap<>();

        for (Long placeId : placeIds) {
            String bookmarkKey = RedisKeyGenerator.generatePlaceBookmarkKey(userId, placeId);

            try {
                Boolean cachedBookmark = cacheService.get(bookmarkKey, Boolean.class);

                if (cachedBookmark != null) {
                    bookmarkMap.put(placeId, cachedBookmark);
                } else {
                    boolean isBookmarked = placeBookmarkRepository.existsByUserIdAndPlaceId(userId, placeId);
                    bookmarkMap.put(placeId, isBookmarked);
                    cacheService.set(bookmarkKey, isBookmarked);
                }
            } catch (Exception e) {
                log.warn("장소 북마크 상태 조회 실패 - userId: {}, placeId: {}", userId, placeId, e);
                boolean isBookmarked = placeBookmarkRepository.existsByUserIdAndPlaceId(userId, placeId);
                bookmarkMap.put(placeId, isBookmarked);
            }
        }

        return bookmarkMap;
    }


    //=== Private Methods ===//

    private void saveToDatabase(final PlaceBookmark bookmark) {
        try {
            placeBookmarkRepository.save(bookmark);
        } catch (DataIntegrityViolationException e) {
            log.info("북마크 중복 저장 시도 (무시) - bookmarkId: {}", bookmark.getId());
        }
    }
}