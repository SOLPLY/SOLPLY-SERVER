package org.sopt.solply_server.domain.place.cache.town;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.sopt.solply_server.domain.place.cache.PlaceEntry;
import org.sopt.solply_server.domain.place.cache.PlaceView;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.CountRow;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.LatestRow;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.PopularRow;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.RatingRow;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DB 직접 조회 비교 구조({@code PlaceListProperties.ListSource#DB_DIRECT})의 읽기 경로.
 * 정적 정렬 다섯 축만 맡는다 — 거리순은 이 구조의 범위 밖이다.
 *
 * <p><b>이 클래스가 "DB 직접"인 근거는 읽는 행의 수다.</b> 동네 전량을 적재한 뒤 자바에서 자르는
 * 것은 여기서 하지 않는다. 정렬·태그 필터·커서 seek·절단을 전부 SQL이 끝내고, 앱이 받는 것은
 * {@code fetchSize}개 이하의 행뿐이다. 그 SQL은 새로 쓰지 않고
 * {@link PlaceListDbQueryRepository}의 것을 그대로 부른다 — 동네별 브랜치 UNION ALL, 커버링
 * 인덱스, seek 술어가 이미 그 안에 있고, 문장을 베껴 오면 두 경로의 의미론이 갈린다.
 *
 * <p><b>한 read view 안에서 번호·정렬 키·표시값을 모두 읽는다.</b> REQUIRES_NEW·읽기 전용·
 * REPEATABLE READ 트랜잭션 하나를 열고 그 안에서
 * <ol>
 *   <li>관련 leaf 동네들의 번호를 <b>한 문장으로</b> 읽고,
 *   <li>커서가 싣고 온 범위 표현과 견주고,
 *   <li>그 정렬의 페이지 문장을 돌리고,
 *   <li>페이지에 실린 장소들의 정렬 키·표시값을 읽는다.
 * </ol>
 * 세 문장이지만 관측 시점은 하나다. 그래서 돌려주는 번호는 돌려주는 행의 번호다 — 번호를 읽은
 * 뒤 다른 트랜잭션이 커밋해도 이 읽기에는 보이지 않는다.
 *
 * <p><b>표시값을 따로 읽는 이유는 비용이지 시점이 아니다.</b> 페이지 문장은 커버링 인덱스 안에서
 * 끝나야 하므로 이름·썸네일 같은 인덱스 밖 컬럼을 그 SELECT에 얹을 수 없다
 * ({@link PlaceListDbQueryRepository#findPopularRows} 주석). 그래서 페이지가 정해진 뒤 그
 * <b>place_id 목록으로만</b> 한 번 더 읽는다. 읽는 행은 페이지 크기만큼이고, 같은 read view라
 * 표시값이 다른 시점의 것이 될 수 없다.
 *
 * <p><b>대기 예산은 여기 없다.</b> 커넥션 확보와 실행 대기를 감싸는 것은 호출부의 일이고
 * (요청 하나당 예산 — {@code TownPlaceListService}), 이 클래스는 동기로 돈다.
 *
 * <p><b>여기서 판정하지 않는 것:</b> 커서의 필터 지문·정렬 축 일치는 호출부가 이미 본다
 * ({@code PlaceService}). 이 클래스가 보는 것은 <b>범위 표현</b> 하나다.
 */
@Component
public class TownDbDirectReader {

    /**
     * 페이지에 실린 장소들의 정렬 키와 표시값. 컬럼 구성은 {@code TownSourceLoader}의 적재 문장과
     * 같다 — 두 구조가 같은 {@link PlaceEntry}·{@link PlaceView}를 만들어야 등가 비교가 선다.
     * 다른 것은 <b>범위</b>뿐이다: 저쪽은 동네 전량, 이쪽은 페이지에 실린 place_id 목록이다.
     */
    private static final String HYDRATE_SQL = """
            SELECT ps.place_id, ps.town_id, ps.tag_bitmask,
                   ps.popular_score, ps.created_at,
                   ps.bookmark_count, ps.review_count, ps.avg_rating,
                   ps.latitude, ps.longitude,
                   ps.name, ps.main_tag_id, ps.thumbnail_file_key
              FROM place_stats ps
             WHERE ps.place_id IN (:placeIds)
            """;

    private final EntityManager em;
    private final TownVersionRepository versionRepository;
    private final PlaceListDbQueryRepository dbQueryRepository;
    private final TransactionTemplate readTransaction;

    public TownDbDirectReader(EntityManager em, TownVersionRepository versionRepository,
            PlaceListDbQueryRepository dbQueryRepository,
            PlatformTransactionManager transactionManager) {
        this.em = em;
        this.versionRepository = versionRepository;
        this.dbQueryRepository = dbQueryRepository;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * 한 요청이 가져간 페이지.
     *
     * @param versions 요청 범위의 <b>모든</b> leaf 동네와 그 번호. 장소가 하나도 걸리지 않은
     *                 동네도 빠지지 않는다 — 다음 커서의 범위 표현이 이 값으로 만들어지므로,
     *                 여기서 동네가 빠지면 같은 요청의 다음 페이지가 다른 범위가 된다
     * @param entries  페이지 순서 그대로의 행. {@code fetchSize} 이하이고, 비어 있을 수 있다
     * @param displays 그 행들의 표시값. 키는 {@code entries}의 place_id 전부다
     */
    public record Page(TownVersions versions, List<PlaceEntry> entries,
                       Map<Long, PlaceView> displays) {

        public Page {
            entries = List.copyOf(entries);
            displays = Map.copyOf(displays);
        }
    }

    /**
     * 이 요청의 한 페이지를 DB에서 읽는다.
     *
     * <p>사용 예 — 호출부가 {@code size + 1}을 떠서 다음 페이지 유무를 보는 기존 규칙 그대로다.
     * <pre>{@code
     * Page page = reader.read(leafTownIds, PlaceSortType.POPULAR,
     *         mainTagId, subTagAIds, subTagBIds, cursor, size + 1);
     * boolean hasNext = page.entries().size() > size;
     * String scope = page.versions().scope();   // 다음 커서에 실을 범위 표현
     * }</pre>
     *
     * @param leafTownIds 확장이 끝난 leaf 동네들. 비어 있으면 호출부의 실수다
     * @param sort        정적 다섯 축 중 하나. 거리순은 이 구조가 맡지 않는다
     * @param cursor      없으면 첫 페이지. 있으면 범위 표현이 지금 번호와 같아야 한다
     * @param fetchSize   떠 올 행 수의 상한. {@code size} 해석은 호출부가 한다
     * @throws BusinessException 커서의 범위 표현이 지금 번호와 다르면
     *                           {@link ErrorCode#EXPIRED_PLACE_CURSOR}
     */
    public Page read(List<Long> leafTownIds, PlaceSortType sort, Long mainTagId,
            List<Long> subTagAIds, List<Long> subTagBIds, PlaceListCursor cursor, int fetchSize) {

        if (leafTownIds.isEmpty()) {
            throw new IllegalArgumentException("조회할 동네가 없다 - leaf 확장이 끝난 목록을 넘길 것");
        }
        requireStaticSort(sort);

        return readTransaction.execute(status -> {
            TownVersions versions = versionRepository.readInCurrentTransaction(leafTownIds);
            requireLiveCursor(cursor, versions);
            if (fetchSize <= 0) {
                return new Page(versions, List.of(), Map.of());
            }
            List<Long> pageIds = pageIds(leafTownIds, sort, mainTagId, subTagAIds, subTagBIds,
                    cursor, fetchSize);
            return hydrate(versions, pageIds);
        });
    }

    /**
     * 커서가 선 범위가 <b>지금</b>의 범위와 같은가. 번호가 오른 동네가 하나라도 있거나, 같은
     * {@code townId} 파라미터인데 leaf 집합이 달라졌으면 그 탐색은 이어 갈 수 없다 — 로컬에 옛
     * 데이터가 남아 있는지와 무관하게 만료다. 두 경우를 한 문자열 비교로 잡는 근거는
     * {@link TownVersions#scope()}에 있다.
     */
    private static void requireLiveCursor(PlaceListCursor cursor, TownVersions versions) {
        if (cursor != null && !cursor.scope().equals(versions.scope())) {
            throw new BusinessException(ErrorCode.EXPIRED_PLACE_CURSOR);
        }
    }

    private static void requireStaticSort(PlaceSortType sort) {
        if (sort == PlaceSortType.DISTANCE) {
            throw new IllegalArgumentException(
                    "거리순은 DB 직접 조회 비교 구조의 범위 밖이다 - 정적 다섯 축만 맡는다");
        }
    }

    /**
     * 페이지의 place_id를 <b>순서대로</b> 얻는다. 정렬·필터·seek·절단은 전부 SQL 안에서 끝나므로
     * 여기서 하는 일은 커서 튜플의 칸을 정렬별로 푸는 것뿐이다.
     *
     * <p><b>칸을 꺼내는 자리가 커서를 싣는 자리와 한 쌍이다</b> ({@code PlaceService}의
     * {@code sortKeys}). 한쪽만 바뀌면 다음 페이지가 조용히 다른 좌표에서 재개된다.
     */
    private List<Long> pageIds(List<Long> townIds, PlaceSortType sort, Long mainTagId,
            List<Long> subTagAIds, List<Long> subTagBIds, PlaceListCursor cursor, int fetchSize) {

        Long cursorPlaceId = cursor == null ? null : cursor.placeId();
        return switch (sort) {
            case POPULAR -> dbQueryRepository.findPopularRows(
                            townIds, mainTagId, subTagAIds, subTagBIds,
                            cursor == null ? null : cursor.key(0), cursorPlaceId, fetchSize)
                    .stream().map(PopularRow::placeId).toList();
            case LATEST -> dbQueryRepository.findLatestRows(
                            townIds, mainTagId, subTagAIds, subTagBIds,
                            cursor == null ? null : (long) cursor.key(0), cursorPlaceId, fetchSize)
                    .stream().map(LatestRow::placeId).toList();
            case RATING -> dbQueryRepository.findRatingRows(
                            townIds, mainTagId, subTagAIds, subTagBIds,
                            cursor == null ? null : cursor.key(0),
                            cursor == null ? null : (long) cursor.key(1),
                            cursorPlaceId, fetchSize)
                    .stream().map(RatingRow::placeId).toList();
            case REVIEW_COUNT -> dbQueryRepository.findReviewCountRows(
                            townIds, mainTagId, subTagAIds, subTagBIds,
                            cursor == null ? null : (long) cursor.key(0), cursorPlaceId, fetchSize)
                    .stream().map(CountRow::placeId).toList();
            case BOOKMARK_COUNT -> dbQueryRepository.findBookmarkCountRows(
                            townIds, mainTagId, subTagAIds, subTagBIds,
                            cursor == null ? null : (long) cursor.key(0), cursorPlaceId, fetchSize)
                    .stream().map(CountRow::placeId).toList();
            case DISTANCE -> throw new IllegalArgumentException("거리순은 여기 오지 않는다");
        };
    }

    /**
     * 페이지에 실린 행의 정렬 키·표시값을 읽어 <b>페이지 순서 그대로</b> 세운다.
     *
     * <p>IN 절은 순서를 약속하지 않으므로 맵으로 받아 {@code pageIds} 순서로 다시 늘어놓는다.
     * 여기서 행이 빠지는 일은 없다 — 같은 read view에서 방금 그 id들을 읽었다.
     */
    private Page hydrate(TownVersions versions, List<Long> pageIds) {
        if (pageIds.isEmpty()) {
            return new Page(versions, List.of(), Map.of());
        }
        Map<Long, PlaceEntry> entryById = new HashMap<>(pageIds.size() * 2);
        Map<Long, PlaceView> viewById = new HashMap<>(pageIds.size() * 2);
        for (Object[] row : readRows(pageIds)) {
            PlaceEntry entry = toEntry(row);
            entryById.put(entry.placeId(), entry);
            viewById.put(entry.placeId(), toView(row));
        }

        List<PlaceEntry> entries = new ArrayList<>(pageIds.size());
        Map<Long, PlaceView> displays = new LinkedHashMap<>(pageIds.size() * 2);
        for (Long placeId : pageIds) {
            PlaceEntry entry = entryById.get(placeId);
            if (entry == null) {
                throw new IllegalStateException(
                        "같은 read view에서 페이지 행이 사라졌다 - placeId=" + placeId);
            }
            entries.add(entry);
            displays.put(placeId, viewById.get(placeId));
        }
        return new Page(versions, entries, displays);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readRows(Collection<Long> placeIds) {
        return em.createNativeQuery(HYDRATE_SQL)
                .setParameter("placeIds", placeIds)
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
