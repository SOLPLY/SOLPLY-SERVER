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
    private final BookmarkTargetValidatorRegistry validatorRegistry;
    private final EntityLoader entityLoader;

    /**
     * 북마크 생성. 이미 북마크한 대상이면 409로 거절한다.
     *
     * <p><b>여기서 이벤트를 발행하지 않는다 (2026-08-07).</b> 예전에는 {@code place_stats}
     * 카운트를 준실시간으로 증분하는 리스너가 있었고 PLACE 북마크만 이벤트를 냈다. 증분을 폐지한
     * 뒤로도 발행측만 남아 소비자 없는 이벤트를 계속 던지고 있었기에 함께 걷어냈다 —
     * 카운트를 고치는 주체는 매시 카운트 배치 하나뿐이다.
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
    }

    /** 대상 종류별 중복 북마크 에러코드. 클라이언트가 장소/코스를 코드로 구분할 수 있도록 나눠 둔다. */
    private static ErrorCode alreadyBookmarked(BookmarkTargetType type) {
        return switch (type) {
            case PLACE -> ErrorCode.ALREADY_BOOKMARKED;
            case COURSE -> ErrorCode.ALREADY_BOOKMARKED_COURSE;
        };
    }

    /** 북마크 삭제 (미존재 시 no-op). 존재 여부 검사가 없으면 없는 북마크에도 DELETE가 나간다. */
    @Transactional
    public void delete(Long userId, BookmarkTargetType type, Long targetId) {
        boolean existed = bookmarkRepository.existsByUserIdAndTargetTypeAndTargetId(userId, type, targetId);
        if (existed) {
            bookmarkRepository.deleteByUserIdAndTargetTypeAndTargetId(userId, type, targetId);
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

}
