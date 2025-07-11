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
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceBookmarkService {

    private final UserRepository userRepository;
    private final PlaceBookmarkRepository placeBookmarkRepository;
    private final PlaceRepository placeRepository;
    private final CacheService cacheService;

    // Redis 키 상수
    private static final String BOOKMARK_KEY_PREFIX = "bookmark";  // 개별 북마크 조회용 키
    private static final String BOOKMARK_USER_KEY_PREFIX = "bookmark:user";  // 사용자별 북마크 목록 조회용 키
    private static final int BOOKMARK_CACHE_TTL = 1; // 1시간 TTL

    /**
     * 북마크 생성
     */
    @Transactional
    public void createPlaceBookmark(Long userId, Long placeId) {
        // 사용자 및 장소 검증
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));
        Place place = placeRepository.findById(placeId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE));

        String bookmarkKey = generateBookmarkKey(userId, placeId);
        String userBookmarkKey = generateUserBookmarkKey(userId);

        // 중복 체크 (Redis에서 먼저 확인)
        if (cacheService.exists(bookmarkKey)) {
            log.warn("이미 북마크된 장소 - userId: {}, placeId: {}", userId, placeId);
            throw new BusinessException(ErrorCode.ALREADY_BOOKMARKED);
        }

        try {
            // 북마크 DTO 생성 (Record의 정적 팩토리 메서드 사용)
            BookmarkRedisDto bookmarkData = BookmarkRedisDto.of(userId, placeId);

            // 개별 북마크 정보 저장 (TTL 1시간)
            cacheService.set(bookmarkKey, bookmarkData, BOOKMARK_CACHE_TTL, TimeUnit.HOURS);

            // 사용자별 북마크 목록에 추가
            addToUserBookmarkList(userBookmarkKey, placeId);

            log.info("북마크 Redis 저장 완료 - userId: {}, placeId: {}", userId, placeId);

        } catch (Exception e) {
            log.error("북마크 Redis 저장 실패 - userId: {}, placeId: {}", userId, placeId, e);
            PlaceBookmark bookmark = PlaceBookmark.builder()
                    .user(user)
                    .place(place)
                    .build();
            saveToDatabase(bookmark);
            log.debug("북마크 DB 저장 완료 - userId: {}, placeId: {}", user.getId(), place.getId());
        }
    }

    // === Private Methods ===

    private String generateBookmarkKey(Long userId, Long placeId) {
        return String.format("%s:%d:%d", BOOKMARK_KEY_PREFIX, userId, placeId);
    }

    private String generateUserBookmarkKey(Long userId) {
        return String.format("%s:%d", BOOKMARK_USER_KEY_PREFIX, userId);
    }

    private void addToUserBookmarkList(String userBookmarkKey, Long placeId) {
        try {
            List<Long> bookmarks = cacheService.getList(userBookmarkKey, Long.class);
            if (bookmarks != null && !bookmarks.contains(placeId)) {
                bookmarks.add(placeId);

                // 수정된 setList 메서드 사용
                cacheService.setList(userBookmarkKey, bookmarks, 24, TimeUnit.HOURS);
            }
        } catch (Exception e) {
            log.warn("사용자 북마크 목록 업데이트 실패 - key: {}", userBookmarkKey, e);
        }
    }

    private void saveToDatabase(PlaceBookmark bookmark) {
        try {
            placeBookmarkRepository.save(bookmark);
        } catch (DataIntegrityViolationException e) {
            log.info("북마크 중복 저장 시도 (무시) - bookmarkId: {}", bookmark.getId());
        }
    }
}