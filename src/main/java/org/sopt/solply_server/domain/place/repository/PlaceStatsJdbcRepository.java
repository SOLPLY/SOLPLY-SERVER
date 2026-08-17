package org.sopt.solply_server.domain.place.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code place_stats}의 문장 중 <b>파라미터 수가 데이터에 따라 변해 JPA 리포지토리에 실을 수
 * 없는 것</b>이 여기 온다. {@link JdbcTemplate}은 호출한 쪽의 스프링 트랜잭션에 그대로
 * 참여하므로 경계 계약은 달라지지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class PlaceStatsJdbcRepository {

    /**
     * 한 UPDATE 문장에 싣는 (장소, 델타) 쌍의 상한. 장소당 파라미터 2개라 문장당 2,000개 —
     * 전 장소가 접혀 들어와도 문장 몇 개로 끝나는 크기이면서, SQL 길이와 파싱 비용이 문장당
     * 일정하게 묶인다.
     */
    static final int CHUNK_SIZE = 1_000;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 장소별로 접힌 델타를 {@code bookmark_count}에 더한다 = <b>아웃박스 소비의 유일한 쓰기
     * 문장</b>. 부르는 곳은 {@code BookmarkCountDeltaProcessor} 하나이고, 장소마다 문장을
     * 왕복하는 대신 파생 테이블 조인 한 문장에 청크 단위로 싣는다.
     *
     * <p><b>{@code GREATEST(0, …)}는 표류가 만든 음수의 방어선이다.</b> 카운트 컬럼이 signed
     * INT라 언더플로 에러가 나지는 않으므로, 막는 것은 실패가 아니라 목록에 "북마크 −3"이
     * 찍히는 것이다. 바닥에 물려 두면 다음 안전망 회차가 원본 기준으로 되맞출 때까지 표시만
     * 눌러 둔다.
     *
     * <p><b>행이 없는 장소는 조인에서 빠지고 그것이 정상이다.</b> 비활성·삭제된 장소는
     * {@code place_stats}에 행이 없으므로("행이 있는 장소 = 목록에 나와도 되는 장소") 그
     * 장소의 델타는 적용될 곳 없이 버려진다. 되살아나는 경로는 어드민 재활성이고, 그쪽은
     * 카운트를 0에서 다시 시작한다.
     *
     * @param deltaByPlace 장소별로 접은 합. 0인 항목은 넣지 말 것 — 쓸 것이 없는데 행을 잠근다
     * @return 문장이 걸린 행 수의 합 = 행이 실재한 장소 수
     */
    public int applyBookmarkDeltas(Map<Long, Integer> deltaByPlace) {
        List<Map.Entry<Long, Integer>> entries = new ArrayList<>(deltaByPlace.entrySet());
        int updated = 0;
        for (int from = 0; from < entries.size(); from += CHUNK_SIZE) {
            updated += applyChunk(entries.subList(from, Math.min(from + CHUNK_SIZE, entries.size())));
        }
        return updated;
    }

    private int applyChunk(List<Map.Entry<Long, Integer>> chunk) {
        StringBuilder sql = new StringBuilder("UPDATE place_stats ps JOIN (");
        Object[] params = new Object[chunk.size() * 2];
        for (int i = 0; i < chunk.size(); i++) {
            sql.append(i == 0 ? "SELECT ? AS place_id, ? AS delta" : " UNION ALL SELECT ?, ?");
            params[i * 2] = chunk.get(i).getKey();
            params[i * 2 + 1] = chunk.get(i).getValue();
        }
        sql.append(") d ON d.place_id = ps.place_id")
                .append(" SET ps.bookmark_count = GREATEST(0, ps.bookmark_count + d.delta)");
        return jdbcTemplate.update(sql.toString(), params);
    }
}
