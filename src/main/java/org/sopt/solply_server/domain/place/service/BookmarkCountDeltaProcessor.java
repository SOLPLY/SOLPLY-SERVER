package org.sopt.solply_server.domain.place.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkCountEventRepository;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkCountEventRepository.PlaceDelta;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotRebuildRequestRepository;
import org.sopt.solply_server.domain.place.repository.PlaceStatsJdbcRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 북마크 카운트 아웃박스의 <b>소비 측</b> — 표식을 찍고, DB가 접은 결과를 받고, 더하고, 표식으로
 * 지운다. 매시 :15 회차가 이 클래스 하나다 ({@code PlaceStatsFacade}).
 *
 * <p><b>존재 이유는 트랜잭션 경계 소유다.</b> {@code PlaceStatsBatchProcessor}와 같은 자리에
 * 나란히 두는 것도 같은 이유이고, 파사드의 {@code try/catch}가 그 경계 <b>바깥</b>에 있어야 한다는
 * 원칙도 그대로다 — 한 메서드로 합치면 실패한 문장 뒤에 커밋을 시도하는 모양이 된다.
 *
 * <p><b>⚠️ 격리 수준은 이 메서드가 트랜잭션을 <em>새로 시작</em>할 때만 적용된다.</b> 이미 열린
 * 트랜잭션에 참여하면 스프링이 지정을 조용히 무시한다 — 근거는
 * {@link PlaceStatsBatchProcessor} javadoc에 <b>한 곳에만</b> 둔다.
 *
 * <p><b>재빌드 요청은 이 트랜잭션 안에서, 마지막 문장으로 올린다.</b> 롤백되면 표식도 요청도
 * 함께 없던 일이 된다. 전표가 0이라 아무것도 쓰지 않은 회차는 요청도 올리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class BookmarkCountDeltaProcessor {

    private final BookmarkCountEventRepository countEventRepository;
    private final PlaceStatsJdbcRepository placeStatsJdbcRepository;
    private final SnapshotRebuildRequestRepository rebuildRequestRepository;

    /**
     * 아웃박스를 한 번 비우며 {@code bookmark_count}에 반영한다. 표시할 전표가 없으면 아무 일도
     * 하지 않는다.
     *
     * <p><b>네 단계가 한 트랜잭션이라 exactly-once가 트랜잭션으로 성립한다.</b> 중간에 죽으면
     * 표식까지 통째로 롤백돼 전표가 처음 상태로 남고 다음 회차가 다시 삼키므로, 이중 적용도
     * 유실도 없다. 회차 내 재시도가 안전한 근거도 이것 하나다 — 멱등성이 아니라 원자성이다.
     *
     * <p><b>표식이 커밋된 채로 남는 상태는 존재하지 않는다.</b> 커밋되면 그 행들은 4단계에서
     * 이미 지워졌고, 실패하면 표식도 없던 일이 된다. 그래서 {@code PROCESSING} 상태도, lease도,
     * 남은 표식을 회수하는 장치도 필요 없다 (V42 주석).
     *
     * <p><b>전표도 id 목록도 JVM에 올리지 않는다.</b> 올라오는 것은 장소별로 접힌 합뿐이라 회차가
     * 힙에 싣는 양이 "전표 수"가 아니라 "그 전표가 건드린 장소 수"에 붙는다. <b>DB가 하는 일이
     * 줄었다는 뜻은 아니다</b> — 표식을 찍는 UPDATE가 전표마다 쓰기를 하나 더한다. 둘을 저울질한
     * 측정은 아직 없다.
     *
     * <p><b>PLACE가 아닌 전표는 합산에서 빠지되 삭제에는 포함된다.</b> 삭제 조건이 표식 하나라
     * 종류를 가리지 않는다 — 발행되지 않는 종류가 생기더라도 영영 남는 쓰레기가 되지 않는다.
     *
     * @return 소비한 전표 수와 실제로 카운트를 고친 장소 수. 둘이 다른 것이 정상이다 —
     *         한 장소의 전표 여러 장이 하나로 접히고, 행이 없는 장소(비활성·삭제)는 세지지 않는다
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeltaResult consumeAndApply() {
        String consumptionId = UUID.randomUUID().toString();
        int claimed = countEventRepository.claimAll(consumptionId);
        if (claimed == 0) {
            return DeltaResult.empty();
        }

        List<PlaceDelta> folded = countEventRepository.sumPlaceDeltas(consumptionId);
        int updatedPlaces = folded.isEmpty()
                ? 0
                : placeStatsJdbcRepository.applyBookmarkDeltas(toDeltaByPlace(folded));
        countEventRepository.deleteClaimed(consumptionId);
        // 삼킨 전표가 있을 때만 알린다 — 빈 회차는 위에서 이미 빠져나갔다
        rebuildRequestRepository.request();
        return new DeltaResult(claimed, updatedPlaces);
    }

    /**
     * 접힌 결과를 적용 문장이 받는 모양으로 옮긴다. 순서를 지키는 맵인 것은 문장의 파라미터가
     * DB가 준 순서 그대로 실리게 하기 위해서다. 같은 장소가 두 번 오지 않는다({@code GROUP BY}).
     */
    private Map<Long, Long> toDeltaByPlace(List<PlaceDelta> folded) {
        Map<Long, Long> deltaByPlace = new LinkedHashMap<>();
        for (PlaceDelta delta : folded) {
            deltaByPlace.put(delta.getTargetId(), delta.getDelta());
        }
        return deltaByPlace;
    }

    /**
     * 한 회차의 소비 결과.
     *
     * @param consumedEvents 표시하고 지운 전표 수 = 지난 회차 이후의 토글 수. 이 회차의 비용 그 자체다
     * @param updatedPlaces  {@code bookmark_count}를 실제로 고친 장소 수
     */
    public record DeltaResult(int consumedEvents, int updatedPlaces) {

        private static final DeltaResult EMPTY = new DeltaResult(0, 0);

        static DeltaResult empty() {
            return EMPTY;
        }
    }
}
