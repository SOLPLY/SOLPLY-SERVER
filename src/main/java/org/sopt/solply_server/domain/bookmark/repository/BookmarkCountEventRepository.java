package org.sopt.solply_server.domain.bookmark.repository;

import java.util.List;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkCountEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 북마크 카운트 아웃박스의 소비 문장들. <b>전표를 JVM으로 올리는 문장이 하나도 없다</b> —
 * 표식을 찍고, DB가 접은 결과만 받고, 표식으로 지운다
 * ({@code docs/design/2026-09-12-stats-commit-snapshot-and-db-delta.md}).
 *
 * <p><b>세 문장은 한 트랜잭션에서 순서대로 불려야 한다.</b> {@link #claimAll} →
 * ({@link #sumPlaceDeltas} → 적용) → {@link #deleteClaimed}. 표식이 트랜잭션 안에서만 존재한다는
 * 것이 exactly-once의 근거이므로, 셋을 갈라 서로 다른 트랜잭션에 두면 커밋된 채 남은 표식이
 * 생기고 그것을 회수할 장치가 없다.
 */
public interface BookmarkCountEventRepository extends JpaRepository<BookmarkCountEvent, Long> {

    /**
     * 장소 하나로 접힌 델타. 소비자가 DB에서 받는 유일한 값이다.
     */
    interface PlaceDelta {

        Long getTargetId();

        long getDelta();
    }

    /**
     * 지금 보이는 전표 전부를 이번 회차의 것으로 표시한다 = <b>소비의 관문</b>.
     *
     * <p><b>이 UPDATE가 옛 {@code SELECT … FOR UPDATE}의 역할을 그대로 넘겨받는다.</b> 현재
     * 읽기라 다른 회차가 잠근 행 앞에서 기다리고, 앞 회차가 커밋하면 그 행은 같은 트랜잭션에서
     * 이미 지워져 있어 이 문장에 걸리지 않는다. 델타 회차와 새벽 안전망을 전표라는 공통 관문에서
     * 직렬화하던 성질이 그대로 남는다. 다만 <b>뒤에 온 회차가 반드시 0행을 표시한다는 뜻은
     * 아니다</b> — 기다리는 동안 들어와 커밋된 전표는 그 회차가 가져간다.
     *
     * <p><b>{@code WHERE consumption_id IS NULL}을 달지 않는다.</b> 표식은 커밋된 채로 남을 수
     * 없으므로 정상 상태에서 두 문장은 같은 집합을 고른다. 다만 어떤 이유로든 표식이 남은 행이
     * 생기면 {@code IS NULL}은 그 행을 집계에서도 삭제에서도 영영 빼놓는다 — 전량 표시는 그
     * 구멍이 없고, 옛 전량 {@code FOR UPDATE}의 "그때까지 쌓인 것을 전부 삼킨다"와도 같다.
     * (조건 없는 UPDATE라 세션에 {@code sql_safe_updates}가 켜져 있으면 거부된다 — JDBC 기본은
     * 꺼짐이고, 켜는 환경이 생기면 이 문장이 먼저 죽는다.)
     *
     * <p><b>근거는 id 순서가 아니라 표식이다.</b> 현재 읽기라 미커밋 행을 만나면 건너뛰지 않고
     * 그 자리에서 기다렸다가 커밋된 모습을 보고 표시한다 — id가 작은 미커밋 전표가 늘 다음 회차로
     * 밀린다는 보장은 없다. 대신 {@link #sumPlaceDeltas}도 {@link #deleteClaimed}도 이 문장이
     * 실제로 표식을 찍은 행만 대상으로 하므로, 적용 없이 사라지는 전표가 없다. 표시가 끝난 뒤
     * 들어온 전표는 표식 없이 남아 다음 회차 몫이 된다.
     *
     * <p><b>조건 없는 UPDATE라 그 시점에 보이는 행마다 잠금을 잡는다.</b> 전표는 넣기만 하고
     * 고치지 않으므로 사용자 경로의 INSERT는 이 잠금과 부딪히지 않고, READ COMMITTED에는 갭 락이
     * 없어 문장이 도는 동안에도 새 전표가 들어온다. "사용자를 전혀 막지 않는다"까지는 아니다 —
     * 같은 행을 건드리는 쓰기가 생기면 그때는 기다린다.
     *
     * @return 이번 회차가 표시한 전표 수 = 지난 회차 이후의 토글 수. 0이면 할 일이 없다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE bookmark_count_events
        SET consumption_id = :consumptionId
        """, nativeQuery = true)
    int claimAll(@Param("consumptionId") String consumptionId);

    /**
     * 이번 회차가 표시한 전표를 <b>장소별로 접어</b> 돌려준다. 접는 일이 DB에 있는 것이 이
     * 구조의 요점이다 — 같은 장소를 천 번 토글한 회차가 행 하나가 된다.
     *
     * <p><b>PLACE가 아닌 전표는 여기서 빠지지만 {@link #deleteClaimed}에는 포함된다.</b> 발행
     * 측이 PLACE만 내므로 지금은 실재하지 않지만, 생기더라도 매 회차 읽히기만 하고 영영 남는
     * 쓰레기가 되지 않아야 한다.
     *
     * <p><b>합이 0인 장소는 {@code HAVING}이 걸러 낸다</b> — 등록·해제를 왕복한 사용자가 그
     * 경우이고, 쓸 것이 없는데 행을 잠글 이유가 없다.
     *
     * <p>{@code CAST(… AS SIGNED)}가 필요한 이유: {@code SUM(TINYINT)}은 MySQL에서 DECIMAL이라
     * 그대로 두면 프로젝션이 {@code BigDecimal}을 변환하는 자리가 생긴다. DB에서 정수로 못 박으면
     * 그 변환이 사라지고, 받는 쪽이 {@code long}인 것도 이 폭에 맞춘 것이다.
     */
    @Query(value = """
        SELECT e.target_id AS targetId,
               CAST(SUM(e.delta) AS SIGNED) AS delta
        FROM bookmark_count_events e
        WHERE e.consumption_id = :consumptionId
          AND e.target_type = 'PLACE'
        GROUP BY e.target_id
        HAVING SUM(e.delta) <> 0
        """, nativeQuery = true)
    List<PlaceDelta> sumPlaceDeltas(@Param("consumptionId") String consumptionId);

    /**
     * 이번 회차가 표시한 전표를 지운다. <b>지우는 대상의 근거는 표식이지 id 범위가 아니다</b> —
     * {@code id <= MAX(id)} 같은 범위 삭제가 왜 유실 경로인지는 {@code V38} 주석에 있다.
     *
     * @return 지운 전표 수. 정상이라면 {@link #claimAll}이 돌려준 수와 같다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        DELETE FROM bookmark_count_events
        WHERE consumption_id = :consumptionId
        """, nativeQuery = true)
    int deleteClaimed(@Param("consumptionId") String consumptionId);
}
