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
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 목록 캐시 세 벌 — 회차 사진({@link PlaceListSnapshot}) · 장소 표시값({@link PlaceViewHolder}) ·
 * 태그 표시값({@link TagViewHolder}) — 을 짓는 <b>유일한</b> 곳. 전량 진입점은 {@link #rebuild()}
 * 하나이고, 그것을 부르는 것은 {@link PlaceListSnapshotScheduler}(기동 한 번 · 10분 주기)와
 * {@link PlaceListSnapshotRefresher}(어드민 커밋 뒤) 둘이다. 어드민 훅은 그 밖에
 * {@link #readView(long)}(장소 한 건)과 {@link #readTagViews()}(태그 전량)도 쓴다.
 *
 * <p><b>쿼리가 세 문장인 것이 이 클래스의 전부다.</b>
 * <ul>
 *   <li>문장 ①은 <b>장소당 한 행</b>이다. 기준 테이블은 {@code place_stats}이고 — 목록에 나와도 되는
 *       장소 = place_stats에 행이 있는 장소라는 불변식이다 — 여기에 {@code places}(좌표·이름)와
 *       MAIN 태그 파생 테이블이 붙는다. FK {@code fk_place_stats_place}가 짝을 보장하므로 INNER
 *       JOIN이 행을 잃지 않는다. 한 행이 엔트리 하나와 {@link PlaceView} 하나로 갈라진다.</li>
 *   <li>문장 ②는 썸네일이다. 한 문장에 합치면 (태그 수 × 이미지 수)의 곱집합이 되고, 그것을
 *       자바에서 다시 접는 비용이 쿼리 하나 아끼는 값보다 크다. 두 문장 모두 place_id 순으로 읽어
 *       오므로 조립은 각 결과를 한 번씩 훑는 선형 작업이다.</li>
 *   <li>문장 ③은 태그 전량이다. 수십 행이라 조건을 걸 값어치가 없고, <b>비활성 태그도 담아야</b>
 *       한다 — 대표 태그 이름을 비우는 판정이 조회 시점에 {@code active}로 이뤄지기 때문이다
 *       ({@link TagView}).</li>
 * </ul>
 *
 * <p><b>JPA를 쓰지 않는 것이 핵심 결정이다.</b> 엔티티로 전량을 읽으면 없애려는 하이드레이션 비용을
 * 배치에서 그대로 다시 치른다 — 조회 경로에서 덜어낸 CPU가 배치로 옮겨갈 뿐이다. {@code Object[]}만
 * 받아 자바에서 record로 접는다.
 *
 * <p><b>읽기를 트랜잭션 하나로 묶는 이유는 세 문장이 같은 일관 읽기를 보게 하기 위해서다.</b>
 * 트랜잭션이 없으면 문장마다 커넥션이 갈려, 그 사이에 커밋된 이미지 변경이 장소 목록과 어긋난
 * 조합으로 실릴 수 있다. <b>세 문장이 같은 Read View를 보는 근거는 격리 수준이 REPEATABLE READ인
 * 것이지 {@code readOnly}가 아니다</b> — {@code readOnly}는 쓰기를 막는 힌트일 뿐 스냅샷을 고정하지
 * 않는다. 격리 수준을 코드에서 고정하지 않으므로 이 성질은 <b>운영 DB의 기본값</b>(MySQL InnoDB
 * 기본 REPEATABLE READ)에 기대고 있다. 기본값을 READ COMMITTED로 내리는 날 이 문단부터 짚을 것.
 *
 * <p><b>{@code REQUIRES_NEW}인 이유는 어드민 훅 때문이다.</b> 어드민 쓰기의 재생성은 커밋
 * <em>뒤에</em> 도는데({@code TransactionSynchronization#afterCommit}), 그 시점에는 이미 끝난
 * 트랜잭션의 자원이 아직 스레드에 묶여 있다. 전파를 기본값으로 두면 이 문장들이 <b>이미 커밋된
 * 트랜잭션에 참여</b>하는 모양이 되므로, 새 트랜잭션을 명시적으로 연다. 트랜잭션이 없는 다른
 * 호출자(스케줄러)에게는 그냥 새 트랜잭션 하나를 여는 것과 같아 달라지는 것이 없다.
 *
 * <p><b>전량 읽기는 어노테이션이 아니라 {@link TransactionTemplate}으로 연다.</b>
 * {@link #rebuild()}는 락을 트랜잭션보다 <em>먼저</em> 잡아야 하는데(근거는
 * {@link PlaceListWriteLock}), 메서드에 {@code @Transactional}을 달면 프록시가 그 반대 순서를
 * 강제한다. 락 안에서 프록시를 다시 타려 해도 자기 호출이라 어노테이션이 조용히 무시되므로,
 * 템플릿을 직접 들고 여는 것이 유일하게 정확한 방법이다.
 *
 * <p><b>동치 계약 — 응답이 바뀌면 안 된다.</b> 표시값은 엔티티 경로와 같은 값을 내야 한다.
 * <ul>
 *   <li>썸네일: {@code Place.getThumbnailFileKey()}가 {@code @OrderBy("displayOrder ASC")} +
 *       {@code findFirst()}이므로, 여기서도 {@code display_order ASC}의 첫 행을 쓴다
 *       (MySQL·하이버네이트 모두 ASC에서 NULL이 앞이라 정렬 결과가 같다).</li>
 *   <li>메인 태그: 첫 MAIN 태그의 <b>id</b>를 활성 여부와 무관하게 담는다. 쿼리에서
 *       {@code t.active = 1}을 걸면 안 된다 — 걸면 비활성 MAIN이 붙은 장소에서 <em>다음</em>
 *       MAIN 태그가 뽑혀 엔티티 경로와 갈린다({@link PlaceView}).</li>
 * </ul>
 */
@Slf4j
@Component
public class PlaceListSnapshotLoader {

    private final EntityManager em;
    private final ImageUrlProvider imageUrlProvider;
    private final PlaceListSnapshot snapshot;
    private final PlaceViewHolder placeViewHolder;
    private final TagViewHolder tagViewHolder;
    private final PlaceListWriteLock writeLock;
    private final PlaceListVersionIssuer versionIssuer;
    /** 전량 읽기 세 문장을 담는 트랜잭션 — 어노테이션을 쓰지 않는 이유는 클래스 javadoc */
    private final TransactionTemplate readTransaction;

    public PlaceListSnapshotLoader(
            EntityManager em,
            ImageUrlProvider imageUrlProvider,
            PlaceListSnapshot snapshot,
            PlaceViewHolder placeViewHolder,
            TagViewHolder tagViewHolder,
            PlaceListWriteLock writeLock,
            PlaceListVersionIssuer versionIssuer,
            PlatformTransactionManager transactionManager) {
        this.em = em;
        this.imageUrlProvider = imageUrlProvider;
        this.snapshot = snapshot;
        this.placeViewHolder = placeViewHolder;
        this.tagViewHolder = tagViewHolder;
        this.writeLock = writeLock;
        this.versionIssuer = versionIssuer;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * 장소당 한 행 — 정렬 축 다섯 + 좌표 + 이름 + 메인 태그 id. <b>SELECT 목록이 곧
     * {@link PlaceListEntry}와 {@link PlaceView}의 필드 목록</b>이라, 축이나 표시 필드를 늘릴 때
     * 두 곳이 함께 움직인다.
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
                   ps.popular_score, ps.created_at,
                   ps.bookmark_count, ps.review_count, ps.avg_rating,
                   p.latitude, p.longitude,
                   p.name,
                   m.tag_id
            FROM place_stats ps
            JOIN places p ON p.id = ps.place_id
            LEFT JOIN (
                SELECT pt.place_id   AS place_id,
                       pt.id         AS pt_id,
                       pt.tag_id     AS tag_id
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
     * <p><b>{@code image_file_key}가 동률의 타이브레이커다.</b> {@code display_order}는 중복도 NULL도
     * 허용해서 그것만으로는 첫 행이 정해지지 않는데, MySQL의 filesort는 안정 정렬이 아니라 같은
     * 키의 행 순서가 실행 계획을 따라 바뀔 수 있다. 그러면 전량과 단건({@link #SINGLE_VIEW_SQL})이
     * 다른 이미지를 골라 "패치된 뒤"와 "다음 회차 뒤"의 썸네일이 갈린다.
     *
     * <p><b>행이 아니라 값을 타이브레이커로 쓴 것은 이 테이블에 대리키가 없기 때문이다.</b>
     * {@code place_images}는 {@code @ElementCollection} 테이블이라 PK도 id 컬럼도 없다
     * ({@code V1}). 값으로 갈라도 목적은 달성된다 — 어느 행이 뽑히든 <b>고르는 값</b>이 하나로
     * 정해지면 두 경로가 갈리지 않는다.
     *
     * <p>목록에 없는 장소의 이미지까지 읽는다 — places와 조인해 거르는 값이 전량 스캔보다 크지 않고,
     * 조립 단계에서 문장 ①이 준 장소 id만 꺼내 쓰므로 결과에 섞이지 않는다.
     */
    private static final String THUMBNAIL_SQL = """
            SELECT pi.place_id, pi.image_file_key
            FROM place_images pi
            ORDER BY pi.place_id, pi.display_order, pi.image_file_key
            """;

    /** 태그 전량 — 수십 행이라 조건을 걸지 않는다. 비활성도 담는 이유는 {@link TagView} */
    private static final String TAG_SOURCE_SQL = """
            SELECT t.id, t.name, t.active
            FROM tags t
            """;

    /**
     * 장소 하나의 표시값 — 전량 재빌드와 <b>같은 규칙</b>을 상관 서브쿼리 둘로 옮긴 것이다
     * (첫 MAIN 태그는 {@code place_tag.id} 오름차순, 썸네일은 {@code display_order} 오름차순 +
     * {@code image_file_key} 타이브레이커 — 근거는 {@link #THUMBNAIL_SQL}).
     *
     * <p>{@code p.active}를 묻지 않는다. 비활성 장소의 뷰가 맵에 남아도 정렬 배열에 그 장소가
     * 없으면 화면에 닿지 않으므로 무해하고, 반대로 여기서 걸러 {@code null}을 내면 "장소가 없다"와
     * "비활성이다"가 같은 값이 되어 패치가 조용히 아무 일도 하지 않는다.
     */
    private static final String SINGLE_VIEW_SQL = """
            SELECT p.name,
                   (SELECT pt.tag_id
                      FROM place_tag pt
                      JOIN tags t ON t.id = pt.tag_id
                     WHERE pt.place_id = p.id AND t.type = 'MAIN'
                     ORDER BY pt.id
                     LIMIT 1),
                   (SELECT pi.image_file_key
                      FROM place_images pi
                     WHERE pi.place_id = p.id
                     ORDER BY pi.display_order, pi.image_file_key
                     LIMIT 1)
            FROM places p
            WHERE p.id = :placeId
            """;

    /**
     * 사진과 표시값 두 벌을 통째로 다시 짓고 교체한다.
     *
     * <p><b>락이 트랜잭션보다 먼저다.</b> 재빌드와 표시값 패치가 한 줄로 서야 어드민 수정이
     * 유실되지 않고(근거는 {@link PlaceListWriteLock}), <b>락을 기다리는 동안 커넥션을 쥐고 있으면
     * 안 된다</b> — 어드민 둘이 동시에 커밋하면 기다리는 쪽이 커넥션을 잡은 채 잠들고, 락을 쥔 쪽은
     * 발급용 커넥션을 하나 더 요구해 풀이 얕을 때 서로를 굶긴다. 그래서 락을 먼저 잡고, 읽기
     * 트랜잭션은 락 안에서 열고 닫는다.
     *
     * <p><b>발급은 읽기 트랜잭션이 닫힌 뒤다.</b> 읽는 동안 발급하면 읽기 커넥션과 발급 커넥션을
     * 동시에 쥐지만, 순서를 이렇게 두면 락 안에서 쥐는 커넥션이 언제나 하나다.
     *
     * <p><b>버전은 여기서, 사진을 완성한 순간에, 한 번만 발급한다.</b> 이것이 "버전↔내용 1:1"
     * 불변식의 근거다 — 남의 버전에 내 내용을 붙이는 경로가 존재하지 않으므로 "버전은 같은데 목록이
     * 다른" 사고가 구조로 봉쇄된다. 홀더({@link PlaceListSnapshot#adopt})는 완성된 사진을 받기만
     * 하고 버전을 찍지 않는다. 번호의 출처는 DB 발급 테이블이다
     * ({@link PlaceListVersionIssuer}) — 이 인스턴스 안의 단조는 락이 발급을 줄 세워 지켜지고,
     * 빌더가 둘 이상이 될 때의 단조는 발급소가 하나인 것이 지켜 준다.
     *
     * <p><b>계약 — 사진 완성과 {@code adopt} 사이가 확장 설계의 삽입 지점이다.</b> 지금 그
     * 사이에 있는 것은 버전 발급 하나이고, 다중 인스턴스판의 아카이브 적재(Redis {@code SET})와
     * 발행({@code PUBLISH NEW_VERSION})이 발급 바로 뒤에 들어간다
     * ({@code docs/design/2026-09-01-multi-instance-snapshot-pipeline.md} §3-1·3-2).
     * 그 자리를 비워 두려고 버전 발급을 홀더에서 이리로 옮겼으니, 그 사이에 다른 관심사를
     * 끼워 넣지 말 것.
     *
     * <p><b>표시값 홀더를 사진보다 먼저 교체한다.</b> 순서가 반대면 새 사진에만 있는 장소가 옛 맵에
     * 없어 조회 경로가 그 행을 건너뛰는 창이 열린다. 먼저 교체하면 그 창이 없고, 남는 것은
     * "삭제된 장소를 옛 사진에서 만나 건너뛰는" 계약상 정상 경로뿐이다. 버전 발급은 홀더 교체보다
     * 앞이다 — 발급이 실패하면 사진도 홀더도 직전 회차 그대로 남아 "실패하면 아무것도 바뀌지
     * 않는다"가 성립한다.
     *
     * <p><b>아래 로그를 지우지 말 것.</b> 나중에 회차 직후 CPU 스파이크가 문제가 됐을 때
     * "몇 행을 몇 ms에 지었는가"가 남아 있지 않으면 원인을 이 경로로 좁힐 수 없다
     * (실측 선례: 6,320개 94~302ms). 버전도 함께 남긴다 — 만료를 호소하는 커서의 버전이 어느
     * 회차였는지는 이 로그 말고 대조할 곳이 없다.
     *
     * @return 스냅샷에 담긴 장소 수
     */
    public int rebuild() {
        return writeLock.call(this::rebuildInLock);
    }

    private int rebuildInLock() {
        long startNanos = System.nanoTime();

        Loaded loaded = readTransaction.execute(status -> new Loaded(readSource(), readTagViews()));
        Source source = loaded.source();
        Map<Long, TagView> tagViews = loaded.tagViews();

        PlaceListIndex fresh = PlaceListIndex.of(source.entries());
        // 발급은 자기 트랜잭션에서 돈다 — 읽기 트랜잭션은 이미 닫혔고 그것이 읽기 전용이라
        // INSERT를 실을 수 없었기 때문이기도 하다. 홀더 교체보다 앞에 두어, 발급이 실패하면
        // 사진도 홀더도 직전 회차 그대로 남는다.
        PlaceListPhoto photo = new PlaceListPhoto(versionIssuer.issue(), fresh);
        placeViewHolder.replaceAll(source.views());
        tagViewHolder.replaceAll(tagViews);
        snapshot.adopt(photo);

        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        log.info("장소 목록 스냅샷 교체 완료 - version={}, places={}, towns={}, arrays={}, tags={},"
                        + " elapsed={}ms",
                photo.version(), fresh.placeCount(), fresh.townCount(), fresh.arrayCount(),
                tagViews.size(), elapsedMs);
        return fresh.placeCount();
    }

    /** 읽기 트랜잭션 하나가 낳는 것 전부 — 이 record가 트랜잭션의 경계를 눈에 보이게 한다 */
    private record Loaded(Source source, Map<Long, TagView> tagViews) {}

    /**
     * 장소 하나의 표시값을 다시 읽는다. 어드민이 이름·이미지·메인 태그를 고친 뒤 그 항목만 갈아
     * 끼우는 경로가 이것이다 ({@link PlaceListSnapshotRefresher#patchPlaceViewAfterCommit}).
     *
     * <p><b>규칙은 전량 재빌드와 같아야 한다.</b> 갈리면 같은 장소가 "패치된 뒤"와 "다음 회차 뒤"에
     * 다르게 보인다 — 재빌드 결과와 패치 결과의 동치는 IT가 지킨다.
     *
     * <p>여기는 {@code @Transactional}을 그대로 둔다 — 호출자({@link PlaceListSnapshotRefresher})가
     * <b>락 안에서</b> 부르므로 순서가 이미 "락 → 트랜잭션"이고, 트랜잭션이 한 겹뿐이라 락을 기다리며
     * 커넥션을 쥐는 창도 없다.
     *
     * @return 장소 행이 없으면 {@code empty} — 호출자는 맵을 건드리지 않는다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<PlaceView> readView(long placeId) {
        List<Object[]> rows = readSingleViewRow(placeId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        return Optional.of(new PlaceView(
                placeId,
                (String) row[0],
                imageUrlProvider.getImageUrl((String) row[2]),
                toNullableLong(row[1])));
    }

    /** 문장 ①이 한 번에 낳는 두 벌 — 순서 값과 표시값이다 */
    private record Source(List<PlaceListEntry> entries, Map<Long, PlaceView> views) {}

    /**
     * 두 결과를 접어 엔트리 목록과 표시값 맵을 만든다. 부분 결과가 캐시로 새지 않는다 — 교체는
     * {@link #rebuildInLock()}이 이 메서드를 끝까지 받은 뒤 한 번뿐이다.
     *
     * <p>중복 스킵을 <b>직전 id 비교</b>로 하는 것은 {@link #LIST_SOURCE_SQL}의
     * {@code ORDER BY ps.place_id}에 기대는 것이다 — 같은 장소의 행이 반드시 붙어 나온다.
     * 그 ORDER BY를 지우면 이 스킵이 조용히 무력해진다.
     */
    private Source readSource() {
        Map<Long, String> thumbnailUrlByPlaceId = readThumbnailUrls();

        List<Object[]> rows = readListSource();
        List<PlaceListEntry> entries = new ArrayList<>(rows.size());
        Map<Long, PlaceView> views = new HashMap<>(rows.size() * 2);
        long previousPlaceId = -1L;
        for (Object[] row : rows) {
            long placeId = ((Number) row[0]).longValue();
            if (placeId == previousPlaceId) {
                continue;   // MAIN 태그가 둘 이상인 비정상 데이터 — 첫 행을 유지한다
            }
            previousPlaceId = placeId;
            entries.add(toEntry(row));
            views.put(placeId, new PlaceView(
                    placeId,
                    (String) row[10],
                    thumbnailUrlByPlaceId.get(placeId),
                    toNullableLong(row[11])));
        }
        return new Source(entries, views);
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

    /**
     * 태그 표시값 전량. 재빌드가 쓰는 것과 <b>같은 메서드</b>를 어드민 태그 훅도 쓴다
     * ({@link PlaceListSnapshotRefresher#refreshTagViewsAfterCommit}) — 태그는 수십 행이라 어느
     * 것이 바뀌었는지 모아 단건으로 읽을 값어치가 없고, 규칙이 하나면 두 경로가 갈릴 자리도 없다.
     *
     * <p>{@code @Transactional}은 훅에서 들어오는 <b>바깥 호출</b>을 위한 것이다.
     * {@link #rebuildInLock()}은 자기 호출이라 어노테이션이 무시되고 이미 열린 읽기 트랜잭션
     * 안에서 도는데, 그것이 의도한 모양이다 — 세 문장이 한 트랜잭션을 함께 쓴다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Map<Long, TagView> readTagViews() {
        List<Object[]> rows = readTagSource();
        Map<Long, TagView> views = new HashMap<>(rows.size() * 2);
        for (Object[] row : rows) {
            long tagId = ((Number) row[0]).longValue();
            views.put(tagId, new TagView(tagId, (String) row[1], toBoolean(row[2])));
        }
        return views;
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readListSource() {
        return em.createNativeQuery(LIST_SOURCE_SQL).getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readThumbnails() {
        return em.createNativeQuery(THUMBNAIL_SQL).getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readTagSource() {
        return em.createNativeQuery(TAG_SOURCE_SQL).getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readSingleViewRow(long placeId) {
        return em.createNativeQuery(SINGLE_VIEW_SQL)
                .setParameter("placeId", placeId)
                .getResultList();
    }

    /**
     * <b>값의 좁힘이 여기서 한 번만 일어난다.</b> 생성일은 커서와 같은 식으로 epoch 초가 되고
     * (근거는 {@code PlaceListDbQueryRepository#findLatestRows}의 왕복 계약), 평점은 DECIMAL(3,2)의
     * 무척도 정수만 {@code ratingToInt}으로 든다 ({@link PlaceListEntry} javadoc).
     *
     * <p>{@code intValueExact}는 의도다 — 컬럼 스케일이 2를 <b>넘는</b> 날 여기서 터진다(정보가 상하는
     * 쪽만 막는다). 그 자리 수가 상수라는 것이 {@code ratingToInt}의 전제이므로, 전제가 깨지면 조용히
     * 값을 버리는 대신 멈춰야 한다.
     */
    private static PlaceListEntry toEntry(Object[] row) {
        int ratingToInt = ((BigDecimal) row[7]).movePointRight(2).intValueExact();
        return new PlaceListEntry(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(),
                ((Number) row[3]).doubleValue(),
                toLocalDateTime(row[4]).toEpochSecond(ZoneOffset.UTC),
                ((Number) row[5]).longValue(),
                ((Number) row[6]).longValue(),
                ratingToInt,
                toNullableDouble(row[8]),
                toNullableDouble(row[9]));
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

    /** MAIN 태그가 없는 장소는 NULL이다 — 0으로 채우면 존재하지 않는 태그를 가리킨다 */
    private static Long toNullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    /** BOOLEAN 컬럼도 드라이버에 따라 {@code Boolean}과 {@code TINYINT}로 갈린다 */
    private static boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        return value instanceof Boolean b ? b : ((Number) value).intValue() != 0;
    }
}
