package org.sopt.solply_server.domain.place.cache.publication;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재빌드 요청 한 행.
 *
 * <p><b>{@link #request()}가 {@code MANDATORY}인 것이 계약이다.</b> 호출자(통계 배치·어드민 쓰기)의
 * 트랜잭션에 참여해야 "통계가 롤백되면 요청도 없던 일이 된다"가 성립한다. 트랜잭션 없이 부르면
 * UPDATE가 그 자리에서 자동 커밋돼 롤백되지 않는 유령 요청이 남으므로, 조용히 넘어가지 않고
 * 예외로 끊는다.
 *
 * <p><b>"훅이 실패했을 때 새 요청을 여는" 메서드가 없는 것도 계약이다.</b> 어드민은 쓰기
 * 트랜잭션 안에서 이미 요청을 올려 두었고, 커밋 뒤 훅은 성공했을 때만 그것을 닫는다 — 실패하면
 * 열린 채로 남으므로 새로 열 것이 없다.
 */
@Repository
@RequiredArgsConstructor
public class SnapshotRebuildRequestRepository {

    private static final String REQUEST_SQL =
            "UPDATE place_list_rebuild_requests SET requested_seq = requested_seq + 1 WHERE id = 1";

    private static final String MY_SEQ_SQL =
            "SELECT requested_seq FROM place_list_rebuild_requests WHERE id = 1";

    private static final String COUNTERS_SQL =
            "SELECT requested_seq, processed_seq FROM place_list_rebuild_requests WHERE id = 1";

    // 만료된 락을 들고 늦게 깨어난 발행자가 표시를 뒤로 되돌리지 못하게 한다
    private static final String MARK_SQL = """
            UPDATE place_list_rebuild_requests SET processed_seq = ?
             WHERE id = 1 AND processed_seq < ?
            """;

    private static final String MARK_SOLELY_MINE_SQL = """
            UPDATE place_list_rebuild_requests SET processed_seq = ?
             WHERE id = 1 AND processed_seq = ? AND requested_seq = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    /**
     * 호출자의 트랜잭션에서 요청을 하나 올리고 그 순번을 돌려준다.
     *
     * <p><b>먼저 영속성 컨텍스트를 flush하는 것이 데드락 방지책이다.</b> 어드민 경로는 엔티티
     * 변경(장소 삭제·태그 수정·이미지 교체)을 들고 여기까지 오는데, JPA는 그 UPDATE/DELETE를
     * 커밋 시점까지 미룬다. 그대로 두면 실제 쓰기 순서가 <b>요청 행 → places/tags</b>가 되고,
     * 같은 장소를 걸친 두 어드민 트랜잭션이 서로의 락을 마주 본다. 여기서 flush하면 이 트랜잭션이
     * 마지막으로 잡는 행이 요청 행 하나로 고정된다.
     *
     * <p>네이티브 SQL만 쓰는 통계 경로에서는 flush할 것이 없어 아무 일도 일어나지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long request() {
        entityManager.flush();
        jdbcTemplate.update(REQUEST_SQL);
        Long mySeq = jdbcTemplate.queryForObject(MY_SEQ_SQL, Long.class);
        if (mySeq == null) {
            throw new IllegalStateException("재빌드 요청 행이 없다");
        }
        return mySeq;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RebuildRequestCounters readCounters() {
        return jdbcTemplate.queryForObject(COUNTERS_SQL, (rs, rowNum) ->
                new RebuildRequestCounters(
                        rs.getLong("requested_seq"), rs.getLong("processed_seq")));
    }

    /** 발행자용. 발행 트랜잭션에 참여한다. */
    public void markProcessed(long observedSeq) {
        jdbcTemplate.update(MARK_SQL, observedSeq, observedSeq);
    }

    /**
     * 어드민 훅용 — <b>자기 요청 하나만</b> 닫는다. 발행 트랜잭션에 참여한다.
     *
     * <p>두 조건이 다 필요하다. {@code processed_seq = mySeq - 1}은 "내 앞에 밀린 일이 없었다",
     * {@code requested_seq = mySeq}는 "내 뒤로 아무도 올리지 않았다"다. 앞 조건이 없으면 이런 일이
     * 난다 — 통계 회차가 seq 1을 올려 두고 아직 발행되지 않은 상태에서 어드민이 seq 2로 자기
     * 장소만 패치해 발행한 뒤, 뒤 조건만 보고 {@code processed_seq = 2}로 올리면 <b>어드민 후보에
     * 들어 있지도 않은 통계 변경이 처리됐다고 표시된다.</b>
     *
     * @return 닫았으면 true. false면 아무것도 닫지 않았다 — 발행자가 원본에서 전량으로 짓는다
     */
    public boolean markProcessedIfSolelyMine(long mySeq) {
        return jdbcTemplate.update(MARK_SOLELY_MINE_SQL, mySeq, mySeq - 1, mySeq) == 1;
    }
}
