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
import org.sopt.solply_server.global.cache.RedisDataProcessor;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookmarkRedisDataProcessor implements RedisDataProcessor {

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
        // 사용자 북마크 목록 키는 스킵
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

        // 중복 체크
        if (placeBookmarkRepository.existsByUserIdAndPlaceId(bookmarkData.userId(), bookmarkData.placeId())) {
            log.debug("이미 DB에 존재하는 북마크 - userId: {}, placeId: {}",
                    bookmarkData.userId(), bookmarkData.placeId());

            // 이미 DB에 있으면 Redis에서 삭제
            cacheService.delete(bookmarkKey);
            return;
        }

        // 엔티티 조회 및 저장
        User user = findUserById(bookmarkData.userId());
        Place place = findPlaceById(bookmarkData.placeId());

        PlaceBookmark bookmark = PlaceBookmark.builder()
                .user(user)
                .place(place)
                .build();
        placeBookmarkRepository.save(bookmark);
        cacheService.delete(bookmarkKey); // db에 저장 후 Redis에서 삭제
    }

    /**
     * 사용자 엔티티 조회
     */
    private User findUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));
    }

    /**
     * 장소 엔티티 조회
     */
    private Place findPlaceById(Long placeId) {
        return placeRepository.findById(placeId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE));
    }
}