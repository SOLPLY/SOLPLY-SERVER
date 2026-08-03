package org.sopt.solply_server.domain.place.repository;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 랭킹 <b>세대</b> 레지스터. {@code place_stats_meta}(V28)의 1행을 읽고 민다.
 *
 * <p><b>세대란 배치 한 회차다.</b> 회차의 {@code calculatedAt}이 그 회차가 쓴 전 행의 공통
 * 식별자이므로 그것을 세대 이름으로 삼는다. 커서는 발급 시점의 세대를 싣고 다니고, 조회는
 * 그 세대에 맞는 점수 컬럼({@code popular_score} / {@code prev_popular_score})으로 정렬한다 —
 * 그래야 스크롤 도중 배치가 돌아도 사용자가 보던 순위가 유지된다.
 *
 * <p><b>{@code MAX(calculated_at)}으로 유도하지 않는 이유.</b> 증분
 * ({@code PlaceStatsRepository#incrementBookmark} 외 3개)이 만드는 신규 행의
 * {@code calculated_at}은 NULL이고, 그 컬럼의 뜻은 "이 <em>행</em>을 마지막으로 정산한 기준 시각"이라
 * 행마다 다를 수 있다. 세대는 회차 <em>전체</em>를 가리키는 값이므로 행에서 유도하면 의미가 오염된다.
 * 별도의 1행 레지스터에 배치가 명시적으로 기록한다.
 *
 * <p><b>엔티티가 아니라 네이티브 쿼리인 이유.</b> 이 테이블은 도메인 개념이 아니라 배치가 미는
 * 레지스터이고, 애플리케이션이 원하는 것은 행이 아니라 <b>long 두 개</b>다(커서에 싣는 형태).
 * 엔티티를 두면 {@code TINYINT} PK 매핑과 ddl-auto 검증만 늘고 얻는 것이 없다.
 * {@code PlaceListDbQueryRepository}와 같은 결의 선택이다.
 *
 * <p><b>슬라이스 테스트 주의:</b> {@code @Repository} 컴포넌트라 {@code @DataJpaTest}가 자동으로
 * 줍지 않는다 — 필요한 슬라이스는 {@code @Import}로 명시해야 한다.
 */
@Repository
@RequiredArgsConstructor
public class PlaceStatsMetaRepository {

    /**
     * "세대 없음"의 표현. 배치가 한 번도 안 돌았거나(메타 NULL), 세대가 필요 없는 정렬
     * (LATEST — {@code created_at}은 배치가 만지지 않는 불변 축이다)이 이 값을 쓴다.
     *
     * <p>0을 쓸 수 있는 근거는 실제 세대가 epoch(1970-01-01 UTC)일 수 없다는 것뿐이다.
     * 그래서 이 값은 <b>현 세대와의 비교에서만</b> 안전하다 — 직전 세대와 비교할 때는
     * "직전 세대가 존재하는가"를 먼저 확인해야 한다. 그러지 않으면 세대 이전에 발급된
     * 커서(generation=0)가 "prev와 일치"로 판정돼, 전부 NULL인 prev 컬럼으로 정렬하는
     * 빈 페이지를 받는다.
     */
    public static final long NO_GENERATION = 0L;

    /** 1행 레지스터의 PK. V28의 {@code CHECK (id = 1)}이 다른 값을 막는다. */
    private static final int SINGLETON_ID = 1;

    private final EntityManager em;

    /**
     * 현 세대와 직전 세대. 값이 없으면 {@link #NO_GENERATION}이다.
     *
     * @param current 현 세대 — 커서를 새로 발급할 때 싣는 값
     * @param prev    직전 세대 — 이 세대의 커서만 {@code prev_popular_score} 정렬을 받는다
     */
    public record Generations(long current, long prev) {

        public boolean hasPrev() {
            return prev != NO_GENERATION;
        }
    }

    /**
     * 세대 두 개를 읽는다. 요청 경로가 페이지마다 1회 부르므로 PK 1행 조회여야 한다.
     *
     * <p>행이 없는 경우까지 받는 것은 방어가 아니라 <b>부팅 순서</b> 때문이다 — V28의 INSERT가
     * 이 행을 만들지만, 마이그레이션 이전 스냅샷으로 복원한 DB 등에서 비어 있을 수 있다.
     * 그때 "세대 없음"으로 답하면 전 커서가 현 세대로 강등돼 오늘과 같은 동작이 된다(오류가 아니다).
     */
    public Generations findGenerations() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT current_generation, prev_generation
                FROM place_stats_meta
                WHERE id = :id
                """)
                .setParameter("id", SINGLETON_ID)
                .getResultList();
        if (rows.isEmpty()) {
            return new Generations(NO_GENERATION, NO_GENERATION);
        }
        Object[] row = rows.get(0);
        return new Generations(toGeneration(row[0]), toGeneration(row[1]));
    }

    /**
     * 현 세대를 직전 세대로 밀고 새 회차를 현 세대로 세운다.
     *
     * <p><b>반드시 {@code upsertAll}과 같은 트랜잭션에서 부를 것.</b> 갈라지면 점수는 새 세대인데
     * 메타는 옛 세대인(또는 그 반대인) 구간이 생기고, 그 구간에 발급된 커서는 존재하지 않는
     * 좌표계를 가리킨다. 단일 UPDATE라 그 자체로는 원자적이지만, 원자성이 필요한 단위는
     * "점수 교체 + 세대 기록" 둘이다.
     *
     * @param calculatedAt 이번 회차의 기준 시각 = 이번 세대의 이름
     * @return 영향 행 수. 레지스터 행이 있으면 1
     */
    public int shiftGeneration(LocalDateTime calculatedAt) {
        return em.createNativeQuery("""
                UPDATE place_stats_meta
                   SET prev_generation = current_generation,
                       current_generation = :calculatedAt
                 WHERE id = :id
                """)
                .setParameter("calculatedAt", calculatedAt)
                .setParameter("id", SINGLETON_ID)
                .executeUpdate();
    }

    /**
     * DATETIME(6) 한 칸을 세대 식별자(epoch 초, UTC 간주)로 좁힌다.
     *
     * <p><b>초로 좁혀도 세대가 뭉개지지 않는 근거는 배치 간격이다</b> — 회차 간격이 1시간이라
     * 두 세대가 같은 초를 가질 수 없다. 마이크로초까지 실으면 커서 토큰만 길어진다.
     * 좁힘은 <b>양쪽에서 똑같이</b> 일어나므로(발급도 비교도 이 메서드를 거친다) 판정은 어긋나지 않는다.
     *
     * <p>{@code ZoneOffset.UTC}는 타임존 변환이 아니라 <b>벽시계 값을 그대로 수로 바꾸는</b>
     * 규약이다 — LATEST 커서가 {@code created_at}에 쓰는 것과 같은 규약이며, 세대는 왕복이
     * 아니라 동등 비교에만 쓰이므로 규약이 일관되기만 하면 된다.
     *
     * <p>반환 타입이 {@code Timestamp}/{@code LocalDateTime}으로 갈리는 것은 드라이버·하이버네이트
     * 조합에 달렸다 — 둘 다 받는다.
     */
    private long toGeneration(Object value) {
        if (value == null) {
            return NO_GENERATION;
        }
        LocalDateTime at = value instanceof LocalDateTime ldt
                ? ldt
                : ((Timestamp) value).toLocalDateTime();
        return at.toEpochSecond(ZoneOffset.UTC);
    }
}
