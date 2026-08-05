package org.sopt.solply_server.domain.place.repository;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 랭킹 <b>버전</b> 레지스터. {@code place_stats_meta}(V29)의 1행을 읽고 민다.
 *
 * <p>버전은 배치 한 회차이고, 그 회차의 {@code calculatedAt}을 epoch 초로 좁힌 값이 이름이다.
 * 커서는 발급 시점의 버전을 싣고 다니며 조회는 {@code WHERE ps.version = :version}으로 그 버전의
 * 행 집합만 본다 — 스크롤 도중 배치가 돌아도 사용자가 보던 순위가 유지된다.
 *
 * <p><b>엔티티가 아니라 네이티브 쿼리인 이유:</b> 이 테이블은 도메인 개념이 아니라 배치가 미는
 * 레지스터이고, 애플리케이션이 원하는 것은 행이 아니라 long 두 개다.
 *
 * <p><b>슬라이스 테스트 주의:</b> {@code @Repository} 컴포넌트라 {@code @DataJpaTest}가 자동으로
 * 줍지 않는다 — 필요한 슬라이스는 {@code @Import}로 명시해야 한다.
 */
@Repository
@RequiredArgsConstructor
public class PlaceStatsMetaRepository {

    /**
     * "버전 없음"의 표현. 배치가 한 번도 안 돌았거나(메타 NULL), 버전이 필요 없는 정렬
     * (LATEST — {@code created_at}은 배치가 만지지 않는 불변 축이다)이 이 값을 쓴다.
     *
     * <p>0을 쓸 수 있는 근거는 실제 버전이 epoch(1970-01-01 UTC)일 수 없다는 것뿐이다.
     * 그래서 <b>직전 버전과 비교하기 전에는 {@link Generations#hasPrev()}로 존재를 먼저 확인해야
     * 한다</b> — 그러지 않으면 버전 0을 실은 커서가 "prev와 일치"로 판정된다.
     */
    public static final long NO_GENERATION = 0L;

    /** 1행 레지스터의 PK. V28의 {@code CHECK (id = 1)}이 다른 값을 막는다. */
    private static final int SINGLETON_ID = 1;

    private final EntityManager em;

    /**
     * 버전을 정하는 <b>유일한</b> 규약. {@code ZoneOffset.UTC}는 타임존 변환이 아니라 벽시계 값을
     * 그대로 수로 바꾸는 약속이며(LATEST 커서가 {@code created_at}에 쓰는 것과 같다), 저장·비교·
     * 커서 발급이 모두 이 한 식을 거치므로 판정이 어긋나지 않는다.
     *
     * <p>초로 좁혀도 버전이 뭉개지지 않는 근거는 배치 간격이다 — 회차 간격이 1시간이라 두 회차가
     * 같은 초를 가질 수 없다.
     */
    public static long toVersion(LocalDateTime calculatedAt) {
        return calculatedAt.toEpochSecond(ZoneOffset.UTC);
    }

    /**
     * 현 버전과 직전 버전. 값이 없으면 {@link #NO_GENERATION}이다.
     *
     * @param current 현 버전 — 커서를 새로 발급할 때 싣는 값
     * @param prev    직전 버전 — 이 버전의 커서만 옛 행 집합으로 서빙된다
     */
    public record Generations(long current, long prev) {

        public boolean hasPrev() {
            return prev != NO_GENERATION;
        }
    }

    /**
     * 버전 두 개를 읽는다. 인기순 요청이 페이지마다 1회 부르므로 PK 1행 조회여야 한다.
     *
     * <p>행이 없는 경우까지 받는 것은 방어가 아니라 부팅 순서 때문이다 — V28의 INSERT가 이 행을
     * 만들지만, 마이그레이션 이전 스냅샷으로 복원한 DB 등에서 비어 있을 수 있다.
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
     * 현 버전을 직전으로 밀고 새 회차를 현 버전으로 세운다.
     *
     * <p><b>반드시 {@code upsertAll}과 같은 트랜잭션에서 부를 것.</b> 갈라지면 행 집합은 새 버전인데
     * 메타는 옛 버전인(또는 그 반대인) 구간이 생기고, 그 구간에 발급된 커서는 존재하지 않는
     * 버전을 가리킨다.
     *
     * @param version 이번 회차의 버전 ({@link #toVersion})
     * @return 영향 행 수. 레지스터 행이 있으면 1
     */
    public int shiftGeneration(long version) {
        return em.createNativeQuery("""
                UPDATE place_stats_meta
                   SET prev_generation = current_generation,
                       current_generation = :version
                 WHERE id = :id
                """)
                .setParameter("version", version)
                .setParameter("id", SINGLETON_ID)
                .executeUpdate();
    }

    /** BIGINT 한 칸. 드라이버가 {@code Long}/{@code BigInteger} 어느 쪽으로 주든 받는다. */
    private long toGeneration(Object value) {
        return value == null ? NO_GENERATION : ((Number) value).longValue();
    }
}
