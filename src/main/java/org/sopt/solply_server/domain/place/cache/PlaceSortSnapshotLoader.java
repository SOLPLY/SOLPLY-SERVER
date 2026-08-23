package org.sopt.solply_server.domain.place.cache;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PlaceSortSnapshot}을 짓는 <b>유일한</b> 곳. 진입점은 {@link #rebuild()}이고 기동 훅
 * ({@code PlaceSortWarmup}) · 카운트 배치 훅({@code PlaceStatsFacade}) · 어드민 커밋 훅
 * ({@code PlaceSortSnapshotRefresher})이 같은 것을 부른다.
 *
 * <p><b>쿼리가 한 문장인 것이 이 클래스의 전부다.</b> 목록에 나와도 되는 장소 = place_stats에 행이
 * 있는 장소라는 불변식이 있으므로 기준 테이블은 place_stats 하나이고, 여기에 붙는 조인은
 * {@code places}의 좌표 둘뿐이다 — 거리순이 요구하는 값인데 place_stats에는 없다. FK
 * {@code fk_place_stats_place}가 짝을 보장하므로 INNER JOIN이 행을 잃지 않는다.
 *
 * <p><b>JPA를 쓰지 않는 근거는 골격 로더와 같다</b> — 없애려는 엔티티 하이드레이션 비용을 배치에서
 * 그대로 다시 치를 이유가 없다. {@code Object[]}만 받아 자바에서 record로 접는다.
 *
 * <p><b>{@code REQUIRES_NEW}인 이유는 어드민 훅 때문이다.</b> 어드민 쓰기의 재생성은 커밋
 * <em>뒤에</em> 도는데({@code TransactionSynchronization#afterCommit}), 그 시점에는 이미 끝난
 * 트랜잭션의 자원이 아직 스레드에 묶여 있다. 전파를 기본값으로 두면 이 쿼리가 <b>이미 커밋된
 * 트랜잭션에 참여</b>하는 모양이 되므로, 새 트랜잭션을 명시적으로 연다. 트랜잭션이 없는 다른 두
 * 훅(기동·배치)에서는 그냥 새 트랜잭션 하나를 여는 것과 같아 달라지는 것이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceSortSnapshotLoader {

    private final EntityManager em;
    private final PlaceSortSnapshot snapshot;

    /**
     * 정렬에 필요한 값 전부. <b>SELECT 목록이 곧 {@link PlaceSortEntry}의 필드 목록</b>이라,
     * 정렬 축을 늘릴 때 두 곳이 함께 움직인다.
     *
     * <p>여기에는 {@code p.active} 조건이 없다 — 있으면 안 된다. 비활성화된 장소가 목록 결과에
     * 남아 있는 창(≤1h)에서 DB 경로는 그 장소를 계속 내보내므로, 여기서 걸러 내면 두 방식의 응답이
     * 그 창에서 갈린다. 행의 존재를 정하는 주체는 어드민 쓰기 경로 하나다
     * ({@code PlaceListDbQueryRepository} javadoc의 불변식).
     */
    private static final String SORT_SOURCE_SQL = """
            SELECT ps.place_id, ps.town_id, ps.tag_bitmask,
                   ps.popular_score, ps.score_calculated_at, ps.created_at,
                   ps.bookmark_count, ps.review_count, ps.avg_rating,
                   p.latitude, p.longitude
            FROM place_stats ps
            JOIN places p ON p.id = ps.place_id
            """;

    /**
     * 스냅샷을 통째로 다시 짓고 교체한다.
     *
     * <p><b>아래 로그를 지우지 말 것.</b> 나중에 배치·어드민 직후 CPU 스파이크가 문제가 됐을 때
     * "몇 행을 몇 ms에 지었는가"가 남아 있지 않으면 원인을 이 경로로 좁힐 수 없다. 골격 스냅샷의
     * 같은 로그와 나란히 읽히도록 형식을 맞춰 둔다(실측 선례: 6,320개 94~302ms).
     *
     * @return 스냅샷에 담긴 장소 수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public int rebuild() {
        long startNanos = System.nanoTime();

        PlaceSortIndex fresh = PlaceSortIndex.of(readEntries());
        snapshot.replace(fresh);

        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        log.info("장소 정렬 스냅샷 교체 완료 - places={}, towns={}, arrays={}, elapsed={}ms",
                fresh.placeCount(), fresh.townCount(), fresh.arrayCount(), elapsedMs);
        return fresh.placeCount();
    }

    @SuppressWarnings("unchecked")
    private List<PlaceSortEntry> readEntries() {
        List<Object[]> rows = em.createNativeQuery(SORT_SOURCE_SQL).getResultList();
        List<PlaceSortEntry> entries = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            entries.add(toEntry(row));
        }
        return entries;
    }

    /**
     * <b>정렬 키의 좁힘이 여기서 한 번만 일어난다.</b> 생성일은 커서와 같은 식으로 epoch 초가 되고
     * (근거는 {@code PlaceListDbQueryRepository#findLatestRows}의 왕복 계약), 평점은 표시용
     * BigDecimal과 비교용 double 두 벌로 갈린다 ({@link PlaceSortEntry} javadoc).
     */
    private static PlaceSortEntry toEntry(Object[] row) {
        BigDecimal avgRating = (BigDecimal) row[8];
        return new PlaceSortEntry(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(),
                ((Number) row[3]).doubleValue(),
                row[4] != null,
                toLocalDateTime(row[5]).toEpochSecond(ZoneOffset.UTC),
                ((Number) row[6]).longValue(),
                ((Number) row[7]).longValue(),
                avgRating,
                avgRating.doubleValue(),
                toNullableDouble(row[9]),
                toNullableDouble(row[10]));
    }

    /**
     * DATETIME 컬럼의 반환 타입은 드라이버·하이버네이트 조합에 따라 {@code Timestamp}와
     * {@code LocalDateTime}으로 갈린다. 어느 쪽이든 <b>벽시계 값 그대로</b> 받아야 한다 —
     * 여기서 타임존 변환이 끼면 커서 초가 DB 경로의 것과 어긋나 페이징이 방식마다 달라진다
     * ({@code PlaceListDbQueryRepository#toLocalDateTime}과 같은 이유).
     */
    private static LocalDateTime toLocalDateTime(Object value) {
        return value instanceof LocalDateTime ldt ? ldt : ((Timestamp) value).toLocalDateTime();
    }

    /** 좌표는 NULL일 수 있다 — 0으로 채우면 기니만 앞바다가 실재 좌표라 "좌표 없음"과 섞인다 */
    private static Double toNullableDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
