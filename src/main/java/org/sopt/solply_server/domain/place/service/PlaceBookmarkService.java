package org.sopt.solply_server.domain.place.service;

import static org.sopt.solply_server.domain.place.util.RedisKeyGenerator.generateBookmarkKey;
import static org.sopt.solply_server.domain.place.util.RedisKeyGenerator.generateUserBookmarkKey;

import java.util.Map;
import java.util.stream.Collectors;
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
import org.sopt.solply_server.global.util.InputValidator;
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

   private static final int BOOKMARK_CACHE_TTL = 1; // 1시간 TTL

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

        String bookmarkKey = generateBookmarkKey(userId, placeId);
        String userBookmarkKey = generateUserBookmarkKey(userId);

        // 중복 체크 (Redis에서 먼저 확인)
        if (cacheService.exists(bookmarkKey)) {
            log.warn("이미 북마크된 장소 - userId: {}, placeId: {}", userId, placeId);
            throw new BusinessException(ErrorCode.ALREADY_BOOKMARKED);
        }

        try {
            // 북마크 DTO 생성 (Record의 정적 팩토리 메서드 사용)
            BookmarkRedisDto bookmarkData = BookmarkRedisDto.createActive(userId, placeId);

            // 개별 북마크 정보 저장 (TTL 1시간)
            cacheService.set(bookmarkKey, bookmarkData, BOOKMARK_CACHE_TTL, TimeUnit.HOURS);

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

    /**
     * 북마크 삭제(단일)
     */
    @Transactional
    public void deletePlaceBookmark(final Long userId, final Long placeId) {
        String bookmarkKey = generateBookmarkKey(userId, placeId);
        String userBookmarkKey = generateUserBookmarkKey(userId);

        // Redis에서 현재 상태 확인
        BookmarkRedisDto currentBookmark = cacheService.get(bookmarkKey, BookmarkRedisDto.class);

        if (currentBookmark != null && currentBookmark.isActive()) {
            // 삭제 마커로 업데이트 (DB 쿼리 없음!)
            BookmarkRedisDto deleteMarker = BookmarkRedisDto.createDeleted(userId, placeId);
            cacheService.set(bookmarkKey, deleteMarker, BOOKMARK_CACHE_TTL, TimeUnit.HOURS);

            log.info("북마크 삭제 마커 설정 완료 - userId: {}, placeId: {}", userId, placeId);
        } else {
            log.warn("삭제할 활성 북마크가 없음 - userId: {}, placeId: {}", userId, placeId);
        }
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


    // === Private Methods ===

    private void saveToDatabase(final PlaceBookmark bookmark) {
        try {
            placeBookmarkRepository.save(bookmark);
        } catch (DataIntegrityViolationException e) {
            log.info("북마크 중복 저장 시도 (무시) - bookmarkId: {}", bookmark.getId());
        }
    }

    /**
     * 사용자별 활성 북마크 장소 썸네일 리스트 조회
     * - Redis에서 활성 북마크 ID 목록을 가져오고, 동네별로 가장 최근에 북마크한 장소를 조회
     * - 만약 Redis에 활성 북마크가 없다면, DB에서 동네별로 가장 최근에 북마크한 장소를 조회
     */
    public List<PlaceBookmark> getRecentBookmarkPlacesByTown(Long userId, List<Long> bookmarkPlaceIds) {
        List<PlaceBookmark> allBookmarks;

        // bookmarkPlaceIds가 비어있으면, DB에서 모든 북마크 조회
        if (InputValidator.isBlank(bookmarkPlaceIds)) {
            log.info("DB에서 사용자의 모든 북마크 조회 후 동네별 필터링 - userId: {}", userId);
            allBookmarks = placeBookmarkRepository.findAllByUserId(userId);
        } else {
            // Redis에서 가져온 활성 북마크
            log.info("Redis 활성 북마크 {}개에 해당하는 모든 북마크 조회 후 동네별 필터링 - userId: {}", bookmarkPlaceIds.size(), userId);
            allBookmarks = placeBookmarkRepository.findAllByUserIdAndPlaceIds(userId, bookmarkPlaceIds);
        }

        log.info("조회된 전체 북마크: {}개", allBookmarks.size());

        // 서비스단에서 동네별 최근 북마크만 필터링
        List<PlaceBookmark> recentBookmarksByTown = filterRecentBookmarksByTown(allBookmarks);

        log.info("동네별 최근 북마크 필터링 완료 - userId: {}, count: {}", userId, recentBookmarksByTown.size());

        return recentBookmarksByTown;
    }

    /**
     * 동네별로 가장 최근 북마크만 필터링
     */
    private List<PlaceBookmark> filterRecentBookmarksByTown(List<PlaceBookmark> allBookmarks) {
        if (allBookmarks.isEmpty()) {
            return List.of();
        }

        // 동네별로 가장 최근 북마크만 선택
        Map<Long, PlaceBookmark> recentByTown = allBookmarks.stream()
                .collect(Collectors.toMap(
                        bookmark -> bookmark.getPlace().getTown().getId(),
                        bookmark -> bookmark,
                        (existing, replacement) -> // 같은 동네인 경우 최신 북마크 선택
                                replacement.getCreatedAt().isAfter(existing.getCreatedAt())
                                        ? replacement : existing
                ));

        // 최신순으로 정렬해서 반환
        return recentByTown.values().stream()
                .sorted((b1, b2) -> b2.getCreatedAt().compareTo(b1.getCreatedAt()))
                .collect(Collectors.toList());
    }


}