package org.sopt.solply_server.domain.place.cache;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PlaceListSnapshot}을 짓는 <b>유일한</b> 곳. 진입점은 {@link #rebuild()} 하나이고,
 * 그것을 부르는 것은 {@link PlaceListSnapshotScheduler}(기동 한 번 · 10분 주기)와
 * {@link PlaceListSnapshotRefresher}(어드민 커밋 뒤) 둘이다.
 *
 * <p><b>쿼리가 두 문장인 것이 이 클래스의 전부다.</b>
 * <ul>
 *   <li>문장 ①은 <b>장소당 한 행</b>이다. 기준 테이블은 {@code place_stats}이고 — 목록에 나와도 되는
 *       장소 = place_stats에 행이 있는 장소라는 불변식이다 — 여기에 {@code places}(좌표·이름)와
 *       MAIN 태그 파생 테이블이 붙는다. FK {@code fk_place_stats_place}가 짝을 보장하므로 INNER
 *       JOIN이 행을 잃지 않는다.</li>
 *   <li>문장 ②는 썸네일이다. 한 문장에 합치면 (태그 수 × 이미지 수)의 곱집합이 되고, 그것을
 *       자바에서 다시 접는 비용이 쿼리 하나 아끼는 값보다 크다. 두 문장 모두 place_id 순으로 읽어
 *       오므로 조립은 각 결과를 한 번씩 훑는 선형 작업이다.</li>
 * </ul>
 *
 * <p><b>JPA를 쓰지 않는 것이 핵심 결정이다.</b> 엔티티로 전량을 읽으면 없애려는 하이드레이션 비용을
 * 배치에서 그대로 다시 치른다 — 조회 경로에서 덜어낸 CPU가 배치로 옮겨갈 뿐이다. {@code Object[]}만
 * 받아 자바에서 record로 접는다.
 *
 * <p><b>{@code @Transactional(readOnly = true)}인 이유는 두 문장이 같은 스냅샷을 보게 하기
 * 위해서다.</b> 트랜잭션이 없으면 문장마다 커넥션이 갈려, 그 사이에 커밋된 이미지 변경이 장소 목록과
 * 어긋난 조합으로 실릴 수 있다.
 *
 * <p><b>{@code REQUIRES_NEW}인 이유는 어드민 훅 때문이다.</b> 어드민 쓰기의 재생성은 커밋
 * <em>뒤에</em> 도는데({@code TransactionSynchronization#afterCommit}), 그 시점에는 이미 끝난
 * 트랜잭션의 자원이 아직 스레드에 묶여 있다. 전파를 기본값으로 두면 이 두 문장이 <b>이미 커밋된
 * 트랜잭션에 참여</b>하는 모양이 되므로, 새 트랜잭션을 명시적으로 연다. 트랜잭션이 없는 다른
 * 호출자(스케줄러)에게는 그냥 새 트랜잭션 하나를 여는 것과 같아 달라지는 것이 없다.
 *
 * <p><b>동치 계약 — 응답이 바뀌면 안 된다.</b> 표시 필드 둘은 엔티티 경로와 같은 값을 내야 한다.
 * <ul>
 *   <li>썸네일: {@code Place.getThumbnailFileKey()}가 {@code @OrderBy("displayOrder ASC")} +
 *       {@code findFirst()}이므로, 여기서도 {@code display_order ASC}의 첫 행을 쓴다
 *       (MySQL·하이버네이트 모두 ASC에서 NULL이 앞이라 정렬 결과가 같다).</li>
 *   <li>메인 태그: 첫 MAIN 태그를 고른 <b>뒤</b> 비활성이면 null로 바꾼다. 쿼리에서
 *       {@code t.active = 1}을 걸면 안 된다 — {@link PlaceListEntry} javadoc 참조.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceListSnapshotLoader {

    private final EntityManager em;
    private final ImageUrlProvider imageUrlProvider;
    private final PlaceListSnapshot snapshot;

    /**
     * 장소당 한 행 — 정렬 축 다섯 + 좌표 + 이름 + 메인 태그. <b>SELECT 목록이 곧
     * {@link PlaceListEntry}의 필드 목록</b>이라, 축이나 표시 필드를 늘릴 때 두 곳이 함께 움직인다.
     *
     * <p>여기에는 {@code p.active} 조건이 없다 — 있으면 안 된다. 행의 존재를 정하는 주체는
     * {@code place_stats} 하나이고, 그것이 DB 경로와 같은 행 집합을 보장하는 근거다
     * ({@code PlaceListDbQueryRepository} javadoc의 불변식).
     *
     * <p>{@code place_tag}·{@code tags}를 파생 테이블로 미리 MAIN만 걸러 두고 LEFT JOIN하므로,
     * 태그가 없거나 MAIN이 아닌 태그만 가진 장소도 행이 하나 남는다. (조건을 바깥 WHERE로 올리면
     * MAIN이 없는 장소가 통째로 사라진다.)
     *
     * <p>{@code m.pt_id} 오름차순은 엔티티의 {@code placeTags} bag 순서(= place_tag PK 순)와
     * 맞추기 위한 것이다. MAIN 태그가 둘 이상인 비정상 데이터에서만 의미가 있다.
     */
    private static final String LIST_SOURCE_SQL = """
            SELECT ps.place_id, ps.town_id, ps.tag_bitmask,
                   ps.popular_score, ps.score_calculated_at, ps.created_at,
                   ps.bookmark_count, ps.review_count, ps.avg_rating,
                   p.latitude, p.longitude,
                   p.name,
                   m.tag_name, m.tag_active
            FROM place_stats ps
            JOIN places p ON p.id = ps.place_id
            LEFT JOIN (
                SELECT pt.place_id   AS place_id,
                       pt.id         AS pt_id,
                       t.name        AS tag_name,
                       t.active      AS tag_active
                FROM place_tag pt
                JOIN tags t ON t.id = pt.tag_id
                WHERE t.type = 'MAIN'
            ) m ON m.place_id = ps.place_id
            ORDER BY ps.place_id, m.pt_id
            """;

    /**
     * 썸네일 후보. 장소별 첫 행만 쓰므로 정렬이 곧 선택 규칙이다
     * ({@code idx_place_images_place_id_order}가 이 순서를 그대로 만든다).
     *
     * <p>목록에 없는 장소의 이미지까지 읽는다 — places와 조인해 거르는 값이 전량 스캔보다 크지 않고,
     * 조립 단계에서 문장 ①이 준 장소 id만 꺼내 쓰므로 결과에 섞이지 않는다.
     */
    private static final String THUMBNAIL_SQL = """
            SELECT pi.place_id, pi.image_file_key
            FROM place_images pi
            ORDER BY pi.place_id, pi.display_order
            """;

    /**
     * 스냅샷을 통째로 다시 짓고 교체한다.
     *
     * <p><b>아래 로그를 지우지 말 것.</b> 나중에 회차 직후 CPU 스파이크가 문제가 됐을 때
     * "몇 행을 몇 ms에 지었는가"가 남아 있지 않으면 원인을 이 경로로 좁힐 수 없다
     * (실측 선례: 6,320개 94~302ms).
     *
     * @return 스냅샷에 담긴 장소 수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public int rebuild() {
        long startNanos = System.nanoTime();

        PlaceListIndex fresh = PlaceListIndex.of(readEntries());
        snapshot.replace(fresh);

        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        log.info("장소 목록 스냅샷 교체 완료 - places={}, towns={}, arrays={}, elapsed={}ms",
                fresh.placeCount(), fresh.townCount(), fresh.arrayCount(), elapsedMs);
        return fresh.placeCount();
    }

    /**
     * 두 결과를 접어 엔트리 목록을 만든다. 부분 결과가 스냅샷으로 새지 않는다 — 교체는
     * {@link #rebuild()}가 이 메서드를 끝까지 받은 뒤 한 번뿐이다.
     *
     * <p>중복 스킵을 <b>직전 id 비교</b>로 하는 것은 {@link #LIST_SOURCE_SQL}의
     * {@code ORDER BY ps.place_id}에 기대는 것이다 — 같은 장소의 행이 반드시 붙어 나온다.
     * 그 ORDER BY를 지우면 이 스킵이 조용히 무력해진다.
     */
    private List<PlaceListEntry> readEntries() {
        Map<Long, String> thumbnailUrlByPlaceId = readThumbnailUrls();

        List<Object[]> rows = readListSource();
        List<PlaceListEntry> entries = new ArrayList<>(rows.size());
        long previousPlaceId = -1L;
        for (Object[] row : rows) {
            long placeId = ((Number) row[0]).longValue();
            if (placeId == previousPlaceId) {
                continue;   // MAIN 태그가 둘 이상인 비정상 데이터 — 첫 행을 유지한다
            }
            previousPlaceId = placeId;
            entries.add(toEntry(row, thumbnailUrlByPlaceId.get(placeId)));
        }
        return entries;
    }

    /**
     * 장소 → 썸네일 URL. 값이 {@code null}인 항목도 <b>키는 남는다</b>.
     *
     * <p>{@code containsKey}로 거르는 것이 계약이다: {@code getImageUrl}은 blank 키에 null을 내는데
     * {@code putIfAbsent}·{@code computeIfAbsent}는 null을 "없음"으로 취급해 다음 이미지를 대신
     * 집어 든다. 엔티티 경로는 그 경우 null 그대로이므로 여기서도 null을 값으로 남겨야 한다.
     */
    private Map<Long, String> readThumbnailUrls() {
        List<Object[]> rows = readThumbnails();
        Map<Long, String> urlByPlaceId = new HashMap<>(rows.size() * 2);
        for (Object[] row : rows) {
            long placeId = ((Number) row[0]).longValue();
            // 첫 행이 display_order가 가장 앞선 이미지다
            if (!urlByPlaceId.containsKey(placeId)) {
                urlByPlaceId.put(placeId, imageUrlProvider.getImageUrl((String) row[1]));
            }
        }
        return urlByPlaceId;
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readListSource() {
        return em.createNativeQuery(LIST_SOURCE_SQL).getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readThumbnails() {
        return em.createNativeQuery(THUMBNAIL_SQL).getResultList();
    }

    /**
     * <b>값의 좁힘이 여기서 한 번만 일어난다.</b> 생성일은 커서와 같은 식으로 epoch 초가 되고
     * (근거는 {@code PlaceListDbQueryRepository#findLatestRows}의 왕복 계약), 평점은 표시용
     * BigDecimal과 비교용 double 두 벌로 갈리며, 이미지는 완성된 URL로 들어온다
     * ({@link PlaceListEntry} javadoc).
     */
    private static PlaceListEntry toEntry(Object[] row, String imageUrl) {
        BigDecimal avgRating = (BigDecimal) row[8];
        return new PlaceListEntry(
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
                toNullableDouble(row[10]),
                (String) row[11],
                imageUrl,
                activeTagNameOrNull(row[12], row[13]));
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

    /**
     * {@code TagViewUtils.getActiveNameOrNull}의 SQL 판 — 이름이 없거나(메인 태그 없음)
     * 비활성이면 null. 두 규칙이 갈리면 목록의 대표 태그가 캐시 on/off에서 달라진다.
     */
    private static String activeTagNameOrNull(Object tagName, Object tagActive) {
        if (tagName == null || tagActive == null) {
            return null;
        }
        boolean active = tagActive instanceof Boolean b ? b : ((Number) tagActive).intValue() != 0;
        return active ? (String) tagName : null;
    }
}
