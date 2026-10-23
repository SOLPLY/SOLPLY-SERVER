package org.sopt.solply_server.domain.place.cache.town;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.sopt.solply_server.domain.place.cache.PlaceEntry;
import org.sopt.solply_server.domain.place.cache.PlaceOrder;
import org.sopt.solply_server.domain.place.cache.PlaceView;
import org.sopt.solply_server.domain.place.config.PlaceListProperties;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 한 동네(또는 여러 동네)의 <b>번호와 원본을 같은 read view에서</b> 읽는다.
 *
 * <p><b>이 클래스의 계약은 하나다 — 돌려주는 번호는 돌려주는 데이터의 번호다.</b> REPEATABLE READ
 * 트랜잭션 하나 안에서 번호를 먼저 읽고 그 뒤 원본을 읽으므로, 둘 사이에 다른 트랜잭션이 커밋해도
 * 이 읽기에는 보이지 않는다. 그래서 "요청이 v10을 봤으니 v10으로 적재하자"가 아니라 "적재해 보니
 * v11이었다"를 정직하게 말할 수 있고, 판단은 호출부가 한다.
 *
 * <p>표시값(이름·썸네일·대표 태그)도 같은 행에서 함께 읽는다. 따로 읽으면 표시값만 다른 시점이
 * 되고, 목록에서 그 장소가 빠지거나 다른 회차의 이름이 붙는다.
 */
@Component
public class TownSourceLoader {

    private static final String SOURCE_SQL = """
            SELECT ps.place_id, ps.town_id, ps.tag_bitmask,
                   ps.popular_score, ps.created_at,
                   ps.bookmark_count, ps.review_count, ps.avg_rating,
                   ps.latitude, ps.longitude,
                   ps.name, ps.main_tag_id, ps.thumbnail_file_key
              FROM place_stats ps
             WHERE ps.town_id IN (:townIds)
            """;

    private final EntityManager em;
    private final TownVersionRepository versionRepository;
    private final PlaceListProperties listProperties;
    private final PlaceListMeters meters;
    private final TransactionTemplate readTransaction;

    public TownSourceLoader(EntityManager em, TownVersionRepository versionRepository,
            PlaceListProperties listProperties, PlaceListMeters meters,
            PlatformTransactionManager transactionManager) {
        this.em = em;
        this.versionRepository = versionRepository;
        this.listProperties = listProperties;
        this.meters = meters;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * 이 동네들을 적재한다.
     *
     * @return 요청한 동네마다 하나씩. 장소가 하나도 없는 동네도 <b>빈 객체로</b> 돌아온다 —
     *         "없음"과 "빈 동네"를 호출부가 구분할 필요가 없게 한다
     */
    public List<TownPlaces> load(Collection<Long> townIds) {
        if (townIds.isEmpty()) {
            return List.of();
        }
        // 적재가 배열을 세우는 구성은 <b>사전 정렬 비교 경로 하나뿐</b>이다. 채택 구조
        // (TOWN_LAZY_SORT)는 객체만 싣고, 배열은 그 축을 처음 요청받은 자리에서 만든다 —
        // 쓰지 않을 축을 적재가 미리 준비하지 않는다는 것이 이 구성의 요지다.
        boolean presorted =
                listProperties.getListSource() == PlaceListProperties.ListSource.TOWN_PRESORTED;
        return readTransaction.execute(status -> {
            TownVersions versions = versionRepository.readInCurrentTransaction(townIds);
            return assemble(townIds, versions, readRows(townIds), presorted);
        });
    }

    /**
     * 요청 하나가 여러 동네를 <b>한 read view에서</b> 관측할 때 쓰는 입구. 적재는 하지 않는다.
     */
    public TownVersions observeVersions(Collection<Long> townIds) {
        return readTransaction.execute(
                status -> versionRepository.readInCurrentTransaction(townIds));
    }

    private List<TownPlaces> assemble(Collection<Long> townIds, TownVersions versions,
            List<Object[]> rows, boolean presorted) {

        Map<Long, List<PlaceEntry>> entriesByTown = new HashMap<>(townIds.size() * 2);
        Map<Long, Map<Long, PlaceView>> displaysByTown = new HashMap<>(townIds.size() * 2);
        for (Long townId : townIds) {
            entriesByTown.put(townId, new ArrayList<>());
            displaysByTown.put(townId, new HashMap<>());
        }
        for (Object[] row : rows) {
            PlaceEntry entry = toEntry(row);
            List<PlaceEntry> bucket = entriesByTown.get(entry.townId());
            if (bucket == null) {
                continue;   // 요청하지 않은 동네 — IN 절에 없으니 올 수 없지만 방어적으로 무시한다
            }
            bucket.add(entry);
            displaysByTown.get(entry.townId()).put(entry.placeId(), toView(row));
        }

        List<TownPlaces> loaded = new ArrayList<>(townIds.size());
        for (Long townId : townIds) {
            long version = versions.versionOf(townId);
            List<PlaceEntry> entries = entriesByTown.get(townId);
            Map<Long, PlaceView> displays = displaysByTown.get(townId);
            // 구성이 실제로 달라야 한다 — objects-only는 여기서 배열도 정렬도 만들지 않는다.
            // 채택 구조에서 그 축의 배열이 실제로 서는 자리는 TownPlaces#order이고,
            // arrayBuilt도 거기서 오른다. 두 자리가 같은 단위(동네 배열 한 벌)를 센다.
            if (presorted) {
                loaded.add(TownPlaces.presorted(townId, version, entries, displays));
                // 동네 하나에 다섯 축이 한꺼번에 선다 — 축마다 1, 적재마다 다섯
                for (PlaceOrder order : PlaceOrder.values()) {
                    meters.arrayBuilt(order.sortType());
                }
            } else {
                loaded.add(TownPlaces.objectsOnly(townId, version, entries, displays));
            }
        }
        return loaded;
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readRows(Collection<Long> townIds) {
        return em.createNativeQuery(SOURCE_SQL)
                .setParameter("townIds", townIds)
                .getResultList();
    }

    private static PlaceView toView(Object[] row) {
        return new PlaceView(
                ((Number) row[0]).longValue(),
                (String) row[10],
                (String) row[12],
                toNullableLong(row[11]));
    }

    private static PlaceEntry toEntry(Object[] row) {
        return new PlaceEntry(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(),
                ((Number) row[3]).doubleValue(),
                toLocalDateTime(row[4]).toEpochSecond(ZoneOffset.UTC),
                ((Number) row[5]).longValue(),
                ((Number) row[6]).longValue(),
                ((BigDecimal) row[7]).movePointRight(2).intValueExact(),
                toNullableDouble(row[8]),
                toNullableDouble(row[9]));
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        return value instanceof LocalDateTime ldt ? ldt : ((Timestamp) value).toLocalDateTime();
    }

    private static Double toNullableDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static Long toNullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
