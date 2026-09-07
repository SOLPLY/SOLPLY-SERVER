package org.sopt.solply_server.domain.place.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkCountEventRepository;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkCountEventRepository.ConsumableEvent;
import org.sopt.solply_server.domain.place.repository.PlaceStatsJdbcRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 북마크 카운트 아웃박스의 <b>소비 측</b> — 전표를 읽고, 접고, 더하고, 읽은 것만 지운다.
 * 매시 카운트 회차의 북마크 축이 이 클래스 하나다 ({@code PlaceStatsFacade}).
 *
 * <p><b>존재 이유는 트랜잭션 경계 소유다.</b> {@code PlaceStatsBatchProcessor}와 같은 자리에
 * 나란히 두는 것도 같은 이유이고, 파사드의 {@code try/catch}가 그 경계 <b>바깥</b>에 있어야 한다는
 * 원칙도 그대로다 — 한 메서드로 합치면 실패한 문장 뒤에 커밋을 시도하는 모양이 된다.
 *
 * <p><b>⚠️ 격리 수준은 이 메서드가 트랜잭션을 <em>새로 시작</em>할 때만 적용된다.</b> 이미 열린
 * 트랜잭션에 참여하면 스프링이 지정을 조용히 무시한다 — 근거는
 * {@link PlaceStatsBatchProcessor} javadoc에 <b>한 곳에만</b> 둔다.
 */
@Component
@RequiredArgsConstructor
public class BookmarkCountDeltaProcessor {

    private final BookmarkCountEventRepository countEventRepository;
    private final PlaceStatsJdbcRepository placeStatsJdbcRepository;

    /**
     * 아웃박스를 한 번 비우며 {@code bookmark_count}에 반영한다. 전표가 없으면 아무 일도 하지 않는다.
     *
     * <p><b>적용과 삭제가 한 트랜잭션이라 exactly-once가 트랜잭션으로 성립한다.</b> 중간에 죽으면
     * 전표가 그대로 남아 다음 회차가 처음부터 소비하므로 이중 적용도 유실도 없고, 회차를 놓치면
     * 쌓였다가 한 번에 소비될 뿐이다. 회차 내 재시도가 안전한 근거도 이것 하나다.
     *
     * <p><b>지우는 대상은 오직 1단계에서 읽은 전표다.</b> {@code id <= MAX(id)} 같은 범위 삭제가
     * 왜 유실 경로인지는 {@link BookmarkCountEventRepository#findAllForConsume} javadoc에 있다.
     * 같은 javadoc이 {@code FOR UPDATE}의 용도도 적어 둔다 — 이 회차와 새벽 안전망 회차를
     * 전표 행이라는 공통 관문으로 직렬화하는 장치다.
     *
     * <p><b>PLACE가 아닌 전표는 합산에서 빼되 삭제 목록에는 남긴다.</b> 발행 측이 PLACE만 내므로
     * 지금은 실재하지 않지만({@code BookmarkService}), 생기더라도 매 회차 읽히기만 하고 영영
     * 남는 쓰레기가 되지 않아야 한다.
     *
     * <p>합이 0인 장소는 건너뛴다 — 등록·해제를 왕복한 사용자가 그 경우이고, 쓸 것이 없는데
     * 행을 잠글 이유가 없다.
     *
     * @return 소비한 전표 수와 실제로 카운트를 고친 장소 수. 둘이 다른 것이 정상이다 —
     *         한 장소의 전표 여러 장이 하나로 접히고, 행이 없는 장소(비활성·삭제)는 세지지 않는다
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeltaResult consumeAndApply() {
        List<ConsumableEvent> consumed = countEventRepository.findAllForConsume();
        if (consumed.isEmpty()) {
            return DeltaResult.empty();
        }

        List<Long> consumedIds = new ArrayList<>(consumed.size());
        Map<Long, Integer> deltaByPlace = new LinkedHashMap<>();
        for (ConsumableEvent event : consumed) {
            consumedIds.add(event.getId());
            if (!BookmarkTargetType.PLACE.name().equals(event.getTargetType())) {
                continue;
            }
            deltaByPlace.merge(event.getTargetId(), event.getDelta(), Integer::sum);
        }
        deltaByPlace.values().removeIf(delta -> delta == 0);

        int updatedPlaces = deltaByPlace.isEmpty()
                ? 0
                : placeStatsJdbcRepository.applyBookmarkDeltas(deltaByPlace);
        countEventRepository.deleteAllByIdInBatch(consumedIds);
        return new DeltaResult(consumed.size(), updatedPlaces);
    }

    /**
     * 한 회차의 소비 결과.
     *
     * @param consumedEvents 읽고 지운 전표 수 = 지난 회차 이후의 토글 수. 이 회차의 비용 그 자체다
     * @param updatedPlaces  {@code bookmark_count}를 실제로 고친 장소 수
     */
    public record DeltaResult(int consumedEvents, int updatedPlaces) {

        private static final DeltaResult EMPTY = new DeltaResult(0, 0);

        static DeltaResult empty() {
            return EMPTY;
        }
    }
}
