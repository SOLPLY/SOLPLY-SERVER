package org.sopt.solply_server.domain.place.cache;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PlaceSkeletonSnapshot}을 짓는 <b>유일한</b> 곳. 진입점은 {@link #rebuild()}이고
 * 기동 훅({@code PlaceSkeletonWarmup})과 카운트 배치 훅({@code PlaceStatsFacade})이 같은 것을 부른다.
 * {@link #loadByIds(List)}는 같은 산출식을 요청 시점에 페이지 id로만 돌리는 측정용 경로다.
 *
 * <p><b>JPA를 쓰지 않는 것이 이 클래스의 핵심 결정이다.</b> {@code findPlacesWithTagsByIds}로
 * 전량을 읽으면 두 가지가 막힌다. (1) {@code placeTags}와 {@code placeImageInfos}가 둘 다 List bag이라
 * 한 번에 페치 조인하면 {@code MultipleBagFetchException}이고, (2) 무엇보다 <b>없애려는 엔티티
 * 하이드레이션 비용을 배치에서 그대로 다시 치른다</b> — 조회 경로에서 덜어낸 CPU가 배치로 옮겨갈 뿐이다.
 * 그래서 네이티브 2쿼리로 {@code Object[]}만 받아 자바에서 Map을 조립한다.
 *
 * <p><b>쿼리를 둘로 나눈 이유.</b> 메인 태그와 썸네일을 한 문장에 넣으면 (태그 수 × 이미지 수)의
 * 곱집합이 되고, 그것을 자바에서 다시 접는 비용이 쿼리 하나 아끼는 값보다 크다.
 * 두 문장 모두 place_id 순으로 읽어 오므로 조립은 각 결과를 한 번씩 훑는 선형 작업이다.
 *
 * <p><b>동치 계약 — 응답이 바뀌면 안 된다.</b> 아래 두 필드는 엔티티 경로와 같은 값을 내야 한다.
 * <ul>
 *   <li>썸네일: {@code Place.getThumbnailFileKey()}가 {@code @OrderBy("displayOrder ASC")} +
 *       {@code findFirst()}이므로, 여기서도 {@code display_order ASC}의 첫 행을 쓴다
 *       (MySQL·하이버네이트 모두 ASC에서 NULL이 앞이라 정렬 결과가 같다).</li>
 *   <li>메인 태그: 첫 MAIN 태그를 고른 <b>뒤</b> 비활성이면 null로 바꾼다. 쿼리에서
 *       {@code t.active = 1}을 걸면 안 된다 — {@link PlaceSkeleton} javadoc 참조.</li>
 * </ul>
 *
 * <p>{@link #rebuild()}는 {@code active = 1}인 장소만 담는다. 비활성화된 장소가 목록 결과에 남아
 * 있는 창(≤1h, {@code PlaceListDbQueryRepository} javadoc)에서는 스냅샷에 그 장소가 없으므로
 * 미스로 떨어지고, 미스 경로가 활성 여부를 묻지 않는 {@code findPlacesWithTagsByIds}로 같은 값을
 * 채운다. {@link #loadByIds(List)}는 그 미스 경로를 대신 서므로 활성 조건을 걸지 <b>않는다</b>.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceSkeletonLoader {

    private final EntityManager em;
    private final ImageUrlProvider imageUrlProvider;
    private final PlaceSkeletonSnapshot snapshot;

    /**
     * 장소 + 메인 태그. {@code place_tag}·{@code tags}를 파생 테이블로 미리 MAIN만 걸러 두고
     * LEFT JOIN하므로, 태그가 없거나 MAIN이 아닌 태그만 가진 장소도 행이 하나 남는다.
     * (조건을 바깥 WHERE로 올리면 MAIN이 없는 장소가 통째로 사라진다.)
     *
     * <p>{@code m.pt_id} 오름차순은 엔티티의 {@code placeTags} bag 순서(= place_tag PK 순)와
     * 맞추기 위한 것이다. MAIN 태그가 둘 이상인 비정상 데이터에서만 의미가 있다.
     *
     * <p><b>WHERE 조각만 파라미터로 빼고 템플릿은 한 벌로 남긴다.</b> 전량(스냅샷)과 id 제한
     * (프로젝션)이 SELECT·파생 테이블·ORDER BY를 공유해야 두 경로가 같은 값을 낸다는 보장이
     * 구조에서 나온다 — 블록을 복사하면 그 보장이 사라진다
     * ({@code PlaceListDbQueryRepository#appendTagFilters}와 같은 이유).
     */
    private static final String PLACES_WITH_MAIN_TAG_SQL_TEMPLATE = """
            SELECT p.id, p.name, p.town_id, m.tag_name, m.tag_active
            FROM places p
            LEFT JOIN (
                SELECT pt.place_id   AS place_id,
                       pt.id         AS pt_id,
                       t.name        AS tag_name,
                       t.active      AS tag_active
                FROM place_tag pt
                JOIN tags t ON t.id = pt.tag_id
                WHERE t.type = 'MAIN'
            ) m ON m.place_id = p.id
            WHERE %s
            ORDER BY p.id, m.pt_id
            """;

    private static final String PLACES_WITH_MAIN_TAG_SQL =
            PLACES_WITH_MAIN_TAG_SQL_TEMPLATE.formatted("p.active = 1");

    /** {@link #loadByIds(List)}의 것 — {@code p.active} 조건이 없는 것이 계약이다. */
    private static final String PLACES_BY_IDS_SQL =
            PLACES_WITH_MAIN_TAG_SQL_TEMPLATE.formatted("p.id IN (:placeIds)");

    /**
     * 썸네일 후보. 장소별 첫 행만 쓰므로 정렬이 곧 선택 규칙이다
     * ({@code idx_place_images_place_id_order}가 이 순서를 그대로 만든다).
     *
     * <p>전량 판은 비활성 장소의 이미지까지 읽는다 — places와 조인해 거르는 값이 전량 스캔보다
     * 크지 않고, 조립 단계에서 활성 장소 id만 꺼내 쓰므로 결과에 섞이지 않는다.
     */
    private static final String THUMBNAIL_SQL_TEMPLATE = """
            SELECT pi.place_id, pi.image_file_key
            FROM place_images pi
            %s
            ORDER BY pi.place_id, pi.display_order
            """;

    private static final String THUMBNAIL_SQL = THUMBNAIL_SQL_TEMPLATE.formatted("");

    private static final String THUMBNAILS_BY_IDS_SQL =
            THUMBNAIL_SQL_TEMPLATE.formatted("WHERE pi.place_id IN (:placeIds)");

    /**
     * 스냅샷을 통째로 다시 짓고 교체한다.
     *
     * <p><b>로그가 이 메서드의 계약 중 하나다.</b> 나중에 배치 직후 CPU 스파이크가 문제가 됐을 때
     * "몇 행을 몇 ms에 지었는가"가 남아 있지 않으면 원인을 이 경로로 좁힐 수 없다.
     *
     * <p>{@code @Transactional(readOnly = true)}인 이유는 두 문장이 <b>같은 스냅샷</b>을 보게 하기
     * 위해서다. 트랜잭션이 없으면 문장마다 커넥션이 갈려, 그 사이에 커밋된 이미지 변경이 장소 목록과
     * 어긋난 조합으로 실릴 수 있다.
     *
     * @return 스냅샷에 담긴 장소 수
     */
    @Transactional(readOnly = true)
    public int rebuild() {
        long startNanos = System.nanoTime();

        Map<Long, PlaceSkeleton> fresh = buildMap(readPlacesWithMainTag(), readThumbnails());
        snapshot.replace(fresh);

        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        log.info("장소 골격 스냅샷 교체 완료 - places={}, elapsed={}ms", fresh.size(), elapsedMs);
        return fresh.size();
    }

    /**
     * 주어진 장소들의 골격만 지어 돌려준다 — 스냅샷에 담지 않고 호출자에게 넘긴다.
     * 두 쿼리와 조립을 {@link #rebuild()}와 공유하므로 값 동치가 구현 공유로 보장된다.
     *
     * <p><b>⚠️ {@code p.active = 1}을 걸지 않는 것이 이 메서드의 계약이다.</b>
     * {@link #rebuild()}는 활성 장소만 담고, 비활성화된 장소가 목록에 남아 있는 창(≤1h)에서는
     * 그 장소가 미스로 떨어져 활성 여부를 묻지 않는 {@code findPlacesWithTagsByIds}가 메운다.
     * 반면 이 메서드는 <b>그 미스 경로를 통째로 대신</b> 서므로, 여기서 활성만 거르면 그 창의
     * 응답에 구멍이 나고 모드 간 응답이 갈린다.
     *
     * <p>트랜잭션 어노테이션을 달지 않는다 — 호출자({@code PlaceService#listPlaces})가 이미
     * {@code @Transactional(readOnly = true)} 안이다. "두 문장이 같은 스냅샷을 본다"는
     * {@link #rebuild()}의 계약도 그 호출자 트랜잭션으로 충족된다.
     */
    public Map<Long, PlaceSkeleton> loadByIds(List<Long> placeIds) {
        if (placeIds.isEmpty()) {
            return Map.of();    // IN () 은 문법 오류다
        }
        return buildMap(readPlacesByIds(placeIds), readThumbnailsByIds(placeIds));
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readPlacesWithMainTag() {
        return em.createNativeQuery(PLACES_WITH_MAIN_TAG_SQL).getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readThumbnails() {
        return em.createNativeQuery(THUMBNAIL_SQL).getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readPlacesByIds(List<Long> placeIds) {
        return em.createNativeQuery(PLACES_BY_IDS_SQL)
                .setParameter("placeIds", placeIds)
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readThumbnailsByIds(List<Long> placeIds) {
        return em.createNativeQuery(THUMBNAILS_BY_IDS_SQL)
                .setParameter("placeIds", placeIds)
                .getResultList();
    }

    /**
     * 두 결과를 접어 완성된 Map을 만든다. 어느 쪽도 부분 결과를 스냅샷에 흘리지 않는다 —
     * 교체는 이 메서드가 끝난 뒤 한 번뿐이다.
     *
     * <p>{@code putIfAbsent}가 "장소별 첫 행"을 고르는 유일한 장치다. 두 쿼리 모두 place_id 순으로
     * 정렬돼 있으므로 첫 행이 곧 (첫 MAIN 태그 / display_order가 가장 앞선 이미지)이다.
     */
    private Map<Long, PlaceSkeleton> buildMap(List<Object[]> placeRows, List<Object[]> imageRows) {
        Map<Long, String> thumbnailUrlByPlaceId = new HashMap<>();
        for (Object[] row : imageRows) {
            long placeId = ((Number) row[0]).longValue();
            // 이미 본 장소는 건너뛴다 — 첫 행이 display_order가 가장 앞선 이미지다.
            // containsKey로 거르는 것이 계약이다: getImageUrl은 blank 키에 null을 내는데
            // computeIfAbsent는 null을 "없음"으로 취급해 다음 이미지를 대신 집어 든다.
            // 엔티티 경로는 그 경우 null 그대로이므로 여기서도 null을 값으로 남겨야 한다.
            if (!thumbnailUrlByPlaceId.containsKey(placeId)) {
                thumbnailUrlByPlaceId.put(placeId, imageUrlProvider.getImageUrl((String) row[1]));
            }
        }

        Map<Long, PlaceSkeleton> fresh = new HashMap<>(placeRows.size() * 2);
        for (Object[] row : placeRows) {
            long placeId = ((Number) row[0]).longValue();
            if (fresh.containsKey(placeId)) {
                continue;   // MAIN 태그가 둘 이상인 비정상 데이터 — 첫 것을 유지한다
            }
            fresh.put(placeId, new PlaceSkeleton(
                    placeId,
                    (String) row[1],
                    thumbnailUrlByPlaceId.get(placeId),
                    activeTagNameOrNull(row[3], row[4]),
                    ((Number) row[2]).longValue()));
        }
        return fresh;
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
