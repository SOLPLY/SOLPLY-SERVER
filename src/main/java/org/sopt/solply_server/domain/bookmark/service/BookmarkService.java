package org.sopt.solply_server.domain.bookmark.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.bookmark.entity.Bookmark;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkCountEvent;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkCountEventRepository;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkRepository;
import org.sopt.solply_server.domain.bookmark.util.BookmarkTargetValidatorRegistry;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookmarkService {

    private final BookmarkRepository bookmarkRepository;
    private final BookmarkCountEventRepository countEventRepository;
    private final BookmarkTargetValidatorRegistry validatorRegistry;
    private final EntityLoader entityLoader;

    /**
     * 북마크 생성. 이미 북마크한 대상이면 409로 거절한다.
     *
     * <p><b>INSERT가 실제로 일어났을 때만 +1 전표를 남긴다.</b> 409로 튕긴 경로는 발행하지
     * 않는다 — 유니크 제약이 "실제 INSERT 1건 = +1 이벤트 1건"을 보장하는 자리다. 전표는 북마크와
     * 같은 트랜잭션에 들어가므로 둘 다 남거나 둘 다 안 남는다.
     *
     * <p>예전에도 같은 자리에 이벤트가 있었지만 성질이 다르다. 그것은 {@code place_stats}를
     * 준실시간으로 증분하는 <em>앱 내 리스너</em>를 향했고, 지금 것은 같은 DB에 행으로 남아
     * 배치 하나가 회차마다 접어서 소비하는 전표다 — 요청 시점에 공유 카운터를 건드리지 않는다는
     * 것이 이 구조의 값어치다 (docs/design/2026-08-17-bookmark-outbox-delta.md 4-3).
     *
     * <p><b>중복 가드가 두 겹인 이유.</b> 앞의 exists 검사는 흔한 경우(이미 북마크한 대상을
     * 다시 누름)를 DB 예외 없이 걸러 준다. 하지만 같은 사용자의 동시 요청 둘은 검사를 <em>둘 다</em>
     * 통과할 수 있고, 그때는 유니크 제약 {@code uk_bookmark_user_target}만이 마지막 방어선이다.
     * {@code save}는 flush를 커밋 시점까지 미루므로 제약 위반이 이 메서드 <em>밖</em>에서 터져
     * 500이 나간다 — {@code saveAndFlush}로 위반을 여기서 받아 같은 409로 되돌린다.
     */
    @Transactional
    public void create(Long userId, BookmarkTargetType type, Long targetId) {
        User user = entityLoader.getUser(userId);
        validatorRegistry.validator(type).validate(targetId);
        if (bookmarkRepository.existsByUserIdAndTargetTypeAndTargetId(userId, type, targetId)) {
            throw new BusinessException(alreadyBookmarked(type));
        }
        try {
            bookmarkRepository.saveAndFlush(Bookmark.create(user, type, targetId));
        } catch (DataIntegrityViolationException e) {
            log.debug("북마크 중복 등록(동시 요청) - userId={}, type={}, targetId={}", userId, type, targetId);
            throw new BusinessException(alreadyBookmarked(type));
        }
        if (publishesCountEvent(type)) {
            countEventRepository.save(BookmarkCountEvent.increment(type, targetId));
        }
    }

    /**
     * 카운트 전표를 낼 대상인지. COURSE는 내지 않는다 — 코스는 카운트를 노출하지 않아 더할 곳이
     * 없고, 소비자 없는 전표는 배치가 매 회차 읽고 지우는 쓰레기다. 코스 카운트를 노출하게 되면
     * 이 분기를 지우는 것이 첫 작업이다.
     */
    private static boolean publishesCountEvent(BookmarkTargetType type) {
        return type == BookmarkTargetType.PLACE;
    }

    /** 대상 종류별 중복 북마크 에러코드. 클라이언트가 장소/코스를 코드로 구분할 수 있도록 나눠 둔다. */
    private static ErrorCode alreadyBookmarked(BookmarkTargetType type) {
        return switch (type) {
            case PLACE -> ErrorCode.ALREADY_BOOKMARKED;
            case COURSE -> ErrorCode.ALREADY_BOOKMARKED_COURSE;
        };
    }

    /**
     * 북마크 삭제 (미존재 시 no-op).
     *
     * <p><b>−1 전표는 영향 행 수가 1일 때만 낸다.</b> 여기 있던 exists 사전 검사를 걷어낸 이유가
     * 그것이다 — 동시 삭제 둘은 검사를 함께 통과할 수 있고, 그 위에 발행을 걸면 행을 지운 쪽이
     * 하나인데 −1이 두 번 쌓인다. DELETE의 반환값은 그 착시를 겪지 않는다.
     */
    @Transactional
    public void delete(Long userId, BookmarkTargetType type, Long targetId) {
        int affected = bookmarkRepository.deleteByUserTarget(userId, type, targetId);
        if (affected == 0) {
            log.debug("삭제할 북마크가 존재하지 않음 - userId={}, type={}, targetId={}", userId, type, targetId);
            return;
        }
        if (publishesCountEvent(type)) {
            countEventRepository.save(BookmarkCountEvent.decrement(type, targetId));
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

}
