package org.sopt.solply_server.domain.place.service.cache;


import static org.sopt.solply_server.global.cache.CachePrefix.BOOKMARK;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import org.sopt.solply_server.global.cache.RedisDataManager;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookmarkRedisDataManager implements RedisDataManager {

    private final CacheService cacheService;
    private final UserRepository userRepository;
    private final PlaceRepository placeRepository;
    private final PlaceBookmarkRepository placeBookmarkRepository;

    @Override
    public String getDomainName() {
        return "BOOKMARK";
    }

    @Override
    public String getKeyPattern() {
        // 개별 북마크 키만 처리 (사용자 목록 키는 제외)
        return "bookmark:*:*";
    }

    @Override
    public void flushToDatabase(String bookmarkKey) {
        // 사용자 북마크 목록 키는 조회용이기 때문에 스킵
        if (bookmarkKey.contains("bookmark:user:")) {
            log.debug("사용자 목록 키 스킵 - key: {}", bookmarkKey);
            return;
        }

        // Redis에서 북마크 데이터 조회
        BookmarkRedisDto bookmarkData = cacheService.get(bookmarkKey, BookmarkRedisDto.class);

        if (bookmarkData == null) {
            log.warn("북마크 데이터가 Redis에 없음 - key: {}", bookmarkKey);
            return;
        }

        // 북마크 상태에 따라 처리
        if (bookmarkData.isActive()) {
            saveActiveBookmark(bookmarkData);
        } else if (bookmarkData.isDeleted()) {
            deleteBookmark(bookmarkKey, bookmarkData);
        }
    }

    @Override
    public void flushAllPendingData() {
        Set<String> keys = cacheService.findKeys(getKeyPattern());

        if (keys.isEmpty()) {
            log.debug("플러시할 북마크 데이터 없음");
        }

        log.info("북마크 플러시 대상: {}개", keys.size());

        for (String key : keys) {
            try {
                flushToDatabase(key);
            } catch (Exception e) {
                log.error("북마크 개별 키 처리 실패 - key: {}", key, e);
            }
        }
    }

    /**
     * 북마크 키로부터 BookmarkRedisDto를 조회하는 메서드
     */
    public BookmarkRedisDto getBookmarkDto(String bookmarkKey) {
        try {
            return cacheService.get(bookmarkKey, BookmarkRedisDto.class);
        } catch (Exception e) {
            log.warn("북마크 DTO 조회 실패 - key: {}", bookmarkKey, e);
            return null;
        }
    }


    /**
     * 활성 북마크의 전체 정보(DTO)를 반환하는 메서드
     */
    public List<BookmarkRedisDto> getActiveBookmarkDtos(Long userId) {
        List<BookmarkRedisDto> activeBookmarkDtos = new ArrayList<>();

        try {
            // 사용자의 모든 북마크 키 스캔
            String userBookmarkPattern = String.format("%s:%d:*", BOOKMARK, userId);
            Set<String> userBookmarkKeys = cacheService.findKeys(userBookmarkPattern);

            for (String bookmarkKey : userBookmarkKeys) {
                BookmarkRedisDto bookmarkDto = cacheService.get(bookmarkKey, BookmarkRedisDto.class);
                if (bookmarkDto != null && bookmarkDto.isActive()) {
                    activeBookmarkDtos.add(bookmarkDto);
                }
            }

            log.info("패턴 스캔으로 활성 북마크 조회 - userId: {}, 활성 북마크 {}개",
                    userId, activeBookmarkDtos.size());

        } catch (Exception e) {
            log.error("Redis에서 북마크 조회 실패 - userId: {}", userId, e);
            return new ArrayList<>();
        }

        return activeBookmarkDtos;
    }


    /**
     * 활성 북마크 처리
     */
    private void saveActiveBookmark(BookmarkRedisDto bookmarkData) {
        try {
            User user = userRepository.findById(bookmarkData.userId())
                    .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));
            Place place = placeRepository.findById(bookmarkData.placeId())
                    .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE));

            PlaceBookmark bookmark = PlaceBookmark.builder()
                    .user(user)
                    .place(place)
                    .build();
            placeBookmarkRepository.save(bookmark);

            log.debug("활성 북마크 DB 저장 완료 - userId: {}, placeId: {}",
                    bookmarkData.userId(), bookmarkData.placeId());

        } catch (DataIntegrityViolationException e) {
            log.debug("이미 DB에 존재하는 북마크 - userId: {}, placeId: {}",
                    bookmarkData.userId(), bookmarkData.placeId());
        }
    }

    /**
     * 삭제 마커 처리
     */
    private void deleteBookmark(String bookmarkKey, BookmarkRedisDto bookmarkData) {
        // DB에서 삭제 (존재하지 않아도 에러 발생하지 않음)
        placeBookmarkRepository.deleteByUserIdAndPlaceId(bookmarkData.userId(), bookmarkData.placeId());

        // 삭제 처리 완료 후 Redis에서도 제거
        cacheService.delete(bookmarkKey);

        log.debug("북마크 삭제 완료 - userId: {}, placeId: {}",
                bookmarkData.userId(), bookmarkData.placeId());
    }

}