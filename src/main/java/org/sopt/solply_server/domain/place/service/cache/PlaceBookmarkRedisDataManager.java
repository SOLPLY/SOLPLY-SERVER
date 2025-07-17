package org.sopt.solply_server.domain.place.service.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.dto.PlaceBookmarkRedisDto;
import org.sopt.solply_server.domain.place.entity.PlaceBookmark;
import org.sopt.solply_server.domain.place.repository.PlaceBookmarkRepository;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.cache.CachePrefix;
import org.sopt.solply_server.global.cache.CacheService;
import org.sopt.solply_server.global.cache.RedisDataManager;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceBookmarkRedisDataManager implements RedisDataManager {

    private final CacheService cacheService;
    private final UserRepository userRepository;
    private final PlaceRepository placeRepository;
    private final PlaceBookmarkRepository placeBookmarkRepository;

    @Override
    public String getDomainName() {
        return "PLACE_BOOKMARK";
    }

    @Override
    public String getKeyPattern() {
        // 개별 북마크 키만 처리 (사용자 목록 키는 제외)
        // Place 북마크 전용 키 패턴
        return "place_bookmark:*:*";
    }

    @Override
    public void flushToDatabase(String bookmarkKey) {
        // 사용자 북마크 목록 키는 조회용이기 때문에 스킵
        if (bookmarkKey.contains("bookmark:user:")) {
            log.debug("사용자 목록 키 스킵 - key: {}", bookmarkKey);
            return;
        }

        // Redis에서 북마크 데이터 조회
        PlaceBookmarkRedisDto bookmarkData = cacheService.get(bookmarkKey, PlaceBookmarkRedisDto.class);

        if (bookmarkData == null) {
            log.warn("북마크 데이터가 Redis에 없음 - key: {}", bookmarkKey);
            return;
        }

        // 북마크 상태에 따라 처리
        if (bookmarkData.isActive()) {
            syncActivePlaceBookmark(bookmarkData);
        } else if (bookmarkData.isDeleted()) {
            syncDeletedPlaceBookmark(bookmarkKey, bookmarkData);
        }
    }

    @Override
    public void flushAllPendingData() {
        Set<String> keys = cacheService.findKeys(getKeyPattern());

        if (keys.isEmpty()) {
            log.debug("플러시할 장소 북마크 데이터 없음");
        }

        log.info("장소 북마크 플러시 대상: {}개", keys.size());

        for (String key : keys) {
            try {
                flushToDatabase(key);
            } catch (Exception e) {
                log.error("장소 북마크 개별 키 처리 실패 - key: {}", key, e);
            }
        }
    }

    /**
     * 북마크 키로부터 BookmarkRedisDto를 조회하는 메서드
     */
    public PlaceBookmarkRedisDto getBookmarkDto(String bookmarkKey) {
        try {
            return cacheService.get(bookmarkKey, PlaceBookmarkRedisDto.class);
        } catch (Exception e) {
            log.warn("장소 북마크 DTO 조회 실패 - key: {}", bookmarkKey, e);
            return null;
        }
    }


    /**
     * 활성 북마크의 전체 정보(DTO)를 반환하는 메서드
     */
    public List<PlaceBookmarkRedisDto> getActivePlaceBookmarkDtos(Long userId) {
        try {
            // 사용자의 모든 북마크 키 스캔
            String userBookmarkPattern = String.format("%s:%d:*",
                    CachePrefix.PLACE_BOOKMARK.getPrefix(), userId);
            Set<String> userBookmarkKeys = cacheService.findKeys(userBookmarkPattern);

            List<PlaceBookmarkRedisDto> activeBookmarkDtos = new ArrayList<>();
            for (String bookmarkKey : userBookmarkKeys) {
                PlaceBookmarkRedisDto bookmarkDto = cacheService.get(bookmarkKey, PlaceBookmarkRedisDto.class);
                if (bookmarkDto != null && bookmarkDto.isActive()) {
                    activeBookmarkDtos.add(bookmarkDto);
                }
            }

            log.info("패턴 스캔으로 활성 장소 북마크 조회 - userId: {}, 활성 북마크 {}개",
                    userId, activeBookmarkDtos.size());

            return activeBookmarkDtos;
        } catch (Exception e) {
            log.error("Redis에서 장소 북마크 조회 실패 - userId: {}", userId, e);
            return new ArrayList<>();
        }
    }


    /**
     * 활성 북마크 처리
     */
    private void syncActivePlaceBookmark(PlaceBookmarkRedisDto bookmarkData) {
        try {
            Long userId = bookmarkData.userId();
            Long placeId = bookmarkData.placeId();

            // 존재하지 않는 사용자나 장소에 대한 북마크는 처리하지 않음
            if (!userRepository.existsById(userId)) {
                log.warn("존재하지 않는 사용자 - userId: {}", userId);
                removeInvalidRedisData(bookmarkData);
                return;
            }

            if (!placeRepository.existsById(placeId)) {
                log.warn("존재하지 않는 장소 - placeId: {}", placeId);
                removeInvalidRedisData(bookmarkData);
                return;
            }

            if (placeBookmarkRepository.existsByUserIdAndPlaceId(userId, placeId)) {
                log.info("이미 존재하는 북마크 - userId: {}, placeId: {}", userId, placeId);
                return; // 이미 DB에 존재하는 북마크는 무시
            }

            PlaceBookmark bookmark = PlaceBookmark.create(
                    placeRepository.getReferenceById(placeId),
                    userRepository.getReferenceById(userId)
            );
            placeBookmarkRepository.save(bookmark);

            log.debug("활성 코스 북마크 DB 동기화 완료 - userId: {}, placeeId: {}",
                    bookmarkData.userId(), bookmarkData.placeId());

        } catch (Exception e) {
            log.error("활성 코스 북마크 동기화 실패 - userId: {}, placeId: {}",
                    bookmarkData.userId(), bookmarkData.placeId(), e);
            throw e;
        }
    }

    /**
     * 유효하지 않은 Redis 데이터 제거
     */
    private void removeInvalidRedisData(PlaceBookmarkRedisDto bookmarkData) {
        String bookmarkKey = String.format("%s:%d:%d",
                CachePrefix.PLACE_BOOKMARK.getPrefix(),
                bookmarkData.userId(),
                bookmarkData.placeId());
        cacheService.delete(bookmarkKey);
        log.debug("유효하지 않은 Redis 데이터 제거 - key: {}", bookmarkKey);
    }

    /**
     * 삭제 마커 처리
     */
    private void syncDeletedPlaceBookmark(String bookmarkKey, PlaceBookmarkRedisDto bookmarkData) {
        // DB에서 삭제 (존재하지 않아도 에러 발생하지 않음)
        placeBookmarkRepository.deleteByUserIdAndPlaceId(bookmarkData.userId(), bookmarkData.placeId());

        // 삭제 처리 완료 후 Redis에서도 제거
        cacheService.delete(bookmarkKey);

        log.debug("북마크 삭제 완료 - userId: {}, placeId: {}",
                bookmarkData.userId(), bookmarkData.placeId());
    }

}