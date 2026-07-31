package org.sopt.solply_server.domain.bookmark.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.bookmark.entity.Bookmark;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkRepository;
import org.sopt.solply_server.domain.bookmark.service.event.PlaceBookmarkCreatedEvent;
import org.sopt.solply_server.domain.bookmark.service.event.PlaceBookmarkDeletedEvent;
import org.sopt.solply_server.domain.bookmark.util.BookmarkTargetValidatorRegistry;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookmarkService {

    private final BookmarkRepository bookmarkRepository;
    private final BookmarkTargetValidatorRegistry validatorRegistry;
    private final EntityLoader entityLoader;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 북마크 생성.
     *
     * <p>PLACE만 증분 이벤트를 발행한다. {@code place_stats}는 장소 전용 테이블인데
     * {@code bookmarks.target_id}는 PLACE와 COURSE가 숫자 공간을 공유하므로,
     * 이 가드를 지우면 코스 북마크가 <b>같은 id의 장소</b> 카운트를 올린다
     * (배치 쪽 동일 계약: {@code 코스_북마크는_같은_id의_장소_점수에_섞이지_않는다}).
     *
     * <p>트랜잭션 안에서 publish해도 리스너는 {@code AFTER_COMMIT}에 돈다 — 이 트랜잭션이
     * 롤백되면 증분도 일어나지 않는다.
     */
    @Transactional
    public void create(Long userId, BookmarkTargetType type, Long targetId) {
        User user = entityLoader.getUser(userId);
        validatorRegistry.validator(type).validate(targetId);
        // IDENTITY 전략이라 save() 시점에 INSERT가 나가고, 그 직전 감사 리스너가 createdAt을 채운다.
        // 이벤트가 실을 시각은 반드시 이 행의 created_at이어야 한다 (PlaceBookmarkCreatedEvent 주석).
        Bookmark saved = bookmarkRepository.save(Bookmark.create(user, type, targetId));
        if (type == BookmarkTargetType.PLACE) {
            eventPublisher.publishEvent(new PlaceBookmarkCreatedEvent(targetId, saved.getCreatedAt()));
        }
    }

    /** 북마크 삭제 (미존재 시 no-op). 실제로 지운 경우에만 PLACE 감분 이벤트를 발행한다. */
    @Transactional
    public void delete(Long userId, BookmarkTargetType type, Long targetId) {
        boolean existed = bookmarkRepository.existsByUserIdAndTargetTypeAndTargetId(userId, type, targetId);
        if (existed) {
            bookmarkRepository.deleteByUserIdAndTargetTypeAndTargetId(userId, type, targetId);
            if (type == BookmarkTargetType.PLACE) {
                eventPublisher.publishEvent(new PlaceBookmarkDeletedEvent(targetId));
            }
        } else {
            log.debug("삭제할 북마크가 존재하지 않음 - userId={}, type={}, targetId={}", userId, type, targetId);
        }
    }

    /** 북마크 여부 확인 (uk 인덱스 point lookup) */
    public boolean isBookmarked(Long userId, BookmarkTargetType type, Long targetId) {
        if (userId == null) return false;
        return bookmarkRepository.existsByUserIdAndTargetTypeAndTargetId(userId, type, targetId);
    }

    /**
     * DB 배치 조회 기반 북마크 여부 맵.
     * 다중 동네에 걸친 장소/코스 목록에 대한 북마크 여부 확인 시 사용.
     * (단일 동네 목록 순서 조회는 Facade에서 DB 직행 경로 사용)
     */
    public Map<Long, Boolean> getBookmarkStatusMap(Long userId, BookmarkTargetType type,
            List<Long> targetIds) {
        if (userId == null || targetIds == null || targetIds.isEmpty()) {
            return targetIds == null ? Map.of()
                    : targetIds.stream().collect(
                            java.util.stream.Collectors.toMap(id -> id, id -> false, (a, b) -> a));
        }
        Set<Long> bookmarkedIds = bookmarkRepository.findBookmarkedTargetIdsByTargetIds(userId, type, targetIds);
        return targetIds.stream()
                .collect(java.util.stream.Collectors.toMap(
                        id -> id, bookmarkedIds::contains, (a, b) -> a));
    }

    /**
     * 내 북마크의 생성 시각 맵. <b>키가 존재하면 곧 "북마크한 상태"이므로 여부 판정도 겸한다</b> —
     * getBookmarkStatusMap 대신 이것을 호출하면 쿼리 수가 늘지 않는다.
     * 표시 카운트 보정이 이 시각을 place_stats.calculated_at과 비교한다.
     *
     * <p>미북마크 대상을 false로 채우던 getBookmarkStatusMap과 달리 <b>키 자체를 넣지 않는다.</b>
     * 호출측이 {@code map.get(id) != null}로 여부를 읽고, 같은 값을 보정 입력으로 그대로 넘긴다.
     *
     * <p>row 타입([Long, LocalDateTime])은 BookmarkRepositoryIT가 못 박아둔다 — 네이티브 쿼리에서
     * TINYINT(1)이 Boolean으로 와 ClassCastException이 난 전례가 있어 캐스팅을 추측으로 두지 않는다.
     *
     * <p>반환 맵은 불변이다 — 호출자 수정이 표시 카운트 보정의 입력을 오염시키는 것을 막는다.
     */
    public Map<Long, LocalDateTime> getMyBookmarkTimesMap(Long userId, BookmarkTargetType type,
            List<Long> targetIds) {
        if (userId == null || targetIds == null || targetIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, LocalDateTime> times = new HashMap<>();
        for (Object[] row : bookmarkRepository.findMyBookmarkTimesByTargetIds(
                userId, type, targetIds)) {
            times.put((Long) row[0], (LocalDateTime) row[1]);
        }
        // 값에 null이 없어 copyOf가 안전하다 — bookmarks.created_at은 NOT NULL이다
        return Map.copyOf(times);
    }
}
