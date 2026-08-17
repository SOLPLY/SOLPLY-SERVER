package org.sopt.solply_server.domain.bookmark.repository;

import java.util.List;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkCountEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BookmarkCountEventRepository extends JpaRepository<BookmarkCountEvent, Long> {

    /**
     * 소비할 전표 한 장의 값 묶음. {@code targetType}은 enum이 아니라 저장 문자열 그대로다 —
     * 소비자는 PLACE 여부만 가리므로 변환 비용을 들일 이유가 없다.
     */
    interface ConsumableEvent {

        Long getId();

        String getTargetType();

        Long getTargetId();

        int getDelta();
    }

    /**
     * 소비할 전표 전량을 id 순으로 잠그고 읽는다.
     *
     * <p><b>⚠️ 엔티티가 아니라 프로젝션으로 읽는 것이 성능 계약이다.</b> 전표 N건이 관리 엔티티로
     * 영속성 컨텍스트에 실리면 같은 트랜잭션의 네이티브 문장마다 전체 플러시가 돌아 더티 체킹이
     * N × 문장 수로 곱해진다 — 2026-08-17 실측에서 이 곱셈 탓에 전표 1만 건 소비(7.4s)가 전량
     * 재계산(2.7s)보다 느렸다. 엔티티 읽기로 되돌리면 그 초선형이 그대로 돌아온다.
     *
     * <p><b>⚠️ 소비자는 이 메서드로 읽은 id만 지워야 한다.</b> {@code id <= MAX(id)} 같은 범위
     * 삭제는 유실 경로다 — auto_increment는 커밋 순서를 보장하지 않아 MAX(id)를 읽는 순간
     * 그보다 작은 id가 아직 미커밋일 수 있고, RC 읽기는 그 행을 집계에서 빠뜨리는데 범위
     * DELETE는 커밋을 기다렸다가 지운다. 적용되지 않은 델타가 사라지는 것이다. 늦게 커밋된
     * 전표는 다음 회차 몫으로 남기면 된다
     * (docs/design/2026-08-17-bookmark-outbox-delta.md 4-2 함정 1).
     *
     * <p><b>FOR UPDATE의 용도는 회차 간 직렬화다.</b> 델타 회차와 새벽 전량 재계산 안전망
     * 회차가 겹치면 둘 다 카운트를 고치고 둘 다 전표를 비우므로, 이벤트 행을 공통 관문으로
     * 삼아 서로를 기다리게 한다. RC 격리에는 갭 락이 없어 <b>신규 INSERT는 막지 않는다</b> —
     * 사용자 쓰기 경로는 배치가 도는 동안에도 그대로 전표를 남기고, 그 행은 위 계약대로 다음
     * 회차가 가져간다.
     */
    @Query(value = """
        SELECT e.id AS id,
               e.target_type AS targetType,
               e.target_id AS targetId,
               e.delta AS delta
        FROM bookmark_count_events e
        ORDER BY e.id
        FOR UPDATE
        """, nativeQuery = true)
    List<ConsumableEvent> findAllForConsume();
}
