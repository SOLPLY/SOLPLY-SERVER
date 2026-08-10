package org.sopt.solply_server.domain.place.repository.querydsl;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.LatestRow;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.PopularRow;
import org.sopt.solply_server.global.config.QueryDslConfig;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 목록 정렬 쿼리의 계약을 실제 MySQL로 못 박는다.
 *
 * <p>검증 대상은 정렬 순서와 커서 의미론이다 — POPULAR (점수 DESC, id ASC),
 * LATEST (생성일 DESC, id DESC), 그리고 각각의 커서 경계. 이 규칙들은 커서를 <em>발급</em>하는
 * {@code PlaceService}와 한 쌍이라, 여기만 바뀌면 페이징이 조용히 어긋난다.
 *
 * <p><b>place_stats는 네이티브 INSERT로 직접 심는다.</b> 엔티티 생성자가 배치 전용으로 봉인돼 있고,
 * 정렬 쿼리는 배치 결과를 <em>읽을</em> 뿐이라 배치를 돌릴 이유가 없다 — 배치를 끼우면 점수 계산
 * 회귀까지 이 파일이 떠안게 되고, 그건 {@code PlaceStatsBatchProcessorIT}의 몫이다.
 *
 * <p><b>뒷정리가 없는 이유.</b> {@code @DataJpaTest}는 테스트마다 트랜잭션을 열고 롤백하므로
 * 여기서 만든 town·place·tag·place_stats 행은 커밋되지 않는다. 같은 싱글턴 컨테이너를 쓰는 다른
 * IT에 place_stats를 남기지 않는다는 것이 중요한데({@code PlaceStatsRepositoryIT}가 빈 테이블을
 * 전제한다) 롤백이 그것을 보장한다. 이 클래스에 커밋하는 테스트를 추가한다면
 * {@code PlaceListFlowIT.cleanUpCommittedFixtures}와 같은 정리를 함께 넣어야 한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, PlaceListDbQueryRepository.class})
class PlaceListDbQueryRepositoryIT extends MySqlContainerSupport {

    /**
     * 메서드 이름은 베이스의 {@code datasource}와 반드시 달라야 한다 (같으면 숨겨져 데이터소스
     * 설정이 통째로 사라진다). 이 클래스는 엔티티↔스키마 정합이 아니라 SQL 동작을 보므로
     * validate가 필요 없다.
     */
    @DynamicPropertySource
    static void ddlAuto(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    PlaceListDbQueryRepository repository;

    @Autowired
    EntityManager em;

    /** 같은 초에 몰린 장소들의 기준 시각. places.created_at이 초 정밀도 DATETIME이라 초까지만 의미 있다. */
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 7, 30, 12, 0, 0);

    /** 페이지 크기를 넘길 일이 없는 넉넉한 한도 — "정렬 결과 전체"를 뜻한다 */
    private static final int NO_LIMIT = 100;

    /** 픽스처 행의 카운트 회차 기준 시각. 이 경로는 잔행 판정을 하지 않아 값 자체에 뜻은 없다 */
    private static final LocalDateTime COUNT_CALCULATED_AT = BASE;

    private long townId;
    private long placeA;   // BASE 1분 전 — 이 town에서 유일하게 오래된 장소
    private long placeB;   // 아래 셋은 created_at이 같은 초 (LATEST 타이브레이크 검증용)
    private long placeC;
    private long placeD;

    @BeforeEach
    void setUp() {
        townId = createTown();
        // id 오름차순 = a < b < c < d 가 되도록 생성 순서를 고정한다. POPULAR의 동점 타이브레이크는
        // id 오름차순, LATEST는 내림차순이라 두 규칙이 서로를 가려주지 못한다 — 순서가 뒤집히면
        // 어느 한쪽 테스트가 반드시 깨진다.
        placeA = createPlace("db모드A", BASE.minusMinutes(1));
        placeB = createPlace("db모드B", BASE);
        placeC = createPlace("db모드C", BASE);
        placeD = createPlace("db모드D", BASE);
    }

    // === POPULAR ===

    @Test
    void 점수_내림차순_동점은_id_오름차순() {
        insertStats(placeA, townId, 4.0, 0);
        insertStats(placeB, townId, 4.0, 0);
        insertStats(placeC, townId, 6.0, 0);

        List<PopularRow> rows = findPopular(null, null, NO_LIMIT);

        // 6.0이 먼저, 동점 4.0 둘은 id 오름차순 — 북마크 검색의 인기순 정렬과 같은 규칙
        assertThat(placeIdsOf(rows)).containsExactly(placeC, placeA, placeB);
        assertThat(rows.get(0).popularScore()).isEqualTo(6.0);
    }

    /**
     * 커서 경계는 "점수 미만 OR (동점 AND id 초과)"다. 동점 구간 한가운데를 커서로 삼아야
     * 두 항 중 하나만 빠져도 결과가 달라진다 — 점수만 비교하면 placeA가 되돌아오고,
     * 등호 분기를 빠뜨리면 동점인 placeB가 통째로 누락된다.
     */
    @Test
    void 커서_경계는_점수_미만_또는_동점_id_초과다() {
        insertStats(placeA, townId, 4.0, 0);
        insertStats(placeB, townId, 4.0, 0);
        insertStats(placeC, townId, 6.0, 0);

        List<PopularRow> rows = findPopular(4.0, placeA, NO_LIMIT);

        assertThat(placeIdsOf(rows)).containsExactly(placeB);
    }

    @Test
    void 태그_필터는_EXISTS_의미론을_유지한다() {
        long mainTagId = createMainTag("db모드메인태그");
        linkTag(placeA, mainTagId);   // 메인 태그를 가진 장소는 A 하나뿐
        insertStats(placeA, townId, 4.0, 0);
        insertStats(placeB, townId, 6.0, 0);
        insertStats(placeC, townId, 8.0, 0);

        List<PopularRow> rows = repository.findPopularRows(
                List.of(townId), mainTagId, null, null, false, null, null, NO_LIMIT);

        // B·C는 점수가 더 높아도 태그가 없으면 나오지 않는다 — 필터가 통째로 빠지면 여기서 3건이 된다
        assertThat(placeIdsOf(rows)).containsExactly(placeA);
    }

    /**
     * <b>타입 내 OR.</b> 서브A에 두 태그를 주면 <em>둘 중 하나만</em> 가진 장소도 통과해야 한다
     * (북마크 검색의 {@code PlaceTagMatcher}와 같은 의미론).
     *
     * <p>서브 태그 EXISTS는 {@code t.id IN (:subTagAIds)}로 메인({@code t.id = :mainTagId})과
     * <b>다른 SQL 분기</b>라 메인 태그 테스트가 이 경로를 대신 물어 주지 못한다. 그래서 여기서
     * 따로 못 박는다.
     *
     * <p>탈락해야 할 placeC에 가장 높은 점수를 주는 것이 핵심이다 — 서브A 블록이 빠지면 C가
     * 결과 맨 앞에 되살아나므로 "정렬은 맞는데 필터만 빠진" 회귀도 순서로 드러난다.
     */
    @Test
    void 서브_태그는_타입_내_OR로_매칭한다() {
        long mainTagId = createMainTag("db모드메인OR");
        long subA1 = createSubTag("db모드서브A1", "OPTION1", mainTagId);
        long subA2 = createSubTag("db모드서브A2", "OPTION1", mainTagId);
        linkTags(placeA, mainTagId, subA1);
        linkTags(placeB, mainTagId, subA2);
        linkTags(placeC, mainTagId);   // 메인만 있고 서브A는 없다 → 탈락
        insertStats(placeA, townId, 4.0, 0);
        insertStats(placeB, townId, 6.0, 0);
        insertStats(placeC, townId, 8.0, 0);

        List<PopularRow> rows = repository.findPopularRows(
                List.of(townId), mainTagId, List.of(subA1, subA2), null, false, null, null, NO_LIMIT);

        assertThat(placeIdsOf(rows)).containsExactly(placeB, placeA);
    }

    /**
     * <b>타입 간 AND.</b> 메인과 서브A는 모두 만족해야 통과한다.
     *
     * <p>두 방향의 불일치를 한 번에 세운다 — placeB는 메인만, placeC는 서브A만 가진다. AND가
     * OR로 새면 셋 다 나오고, 어느 한 EXISTS가 빠지면 그쪽 방향의 탈락자가 되살아난다.
     * placeC에 가장 높은 점수를 준 것은 위와 같은 이유다.
     */
    @Test
    void 태그_타입_간에는_AND로_결합한다() {
        long mainTagId = createMainTag("db모드메인AND");
        long otherMainTagId = createMainTag("db모드메인AND타");
        long subA = createSubTag("db모드서브A", "OPTION1", mainTagId);
        linkTags(placeA, mainTagId, subA);         // 둘 다 만족 → 통과
        linkTags(placeB, mainTagId);               // 서브A 불일치 → 탈락
        linkTags(placeC, otherMainTagId, subA);    // 메인 불일치 → 탈락
        insertStats(placeA, townId, 4.0, 0);
        insertStats(placeB, townId, 6.0, 0);
        insertStats(placeC, townId, 8.0, 0);

        List<PopularRow> rows = repository.findPopularRows(
                List.of(townId), mainTagId, List.of(subA), null, false, null, null, NO_LIMIT);

        assertThat(placeIdsOf(rows)).containsExactly(placeA);
    }

    /**
     * <b>메인 태그가 없으면 서브 태그는 무시한다.</b> 구현의 {@code useSubA = useMainTag && ...}
     * 가드가 그 일을 하고, 북마크 검색의 {@code PlaceTagMatcher}도 {@code mainTagId == null}이면
     * 원본을 그대로 돌려준다 — 두 경로가 여기서 갈리면 같은 요청이 경로마다 다른 답을 낸다.
     *
     * <p>서브A를 가진 장소를 <em>하나만</em> 두어(placeA) 가드가 사라지면 결과가 1건으로
     * 쪼그라들게 만든다 — 전부가 서브A를 가지면 가드를 지워도 테스트가 통과해 버린다.
     */
    @Test
    void 메인_태그가_없으면_서브_태그_조건은_무시한다() {
        long mainTagId = createMainTag("db모드메인무시");
        long subA = createSubTag("db모드서브A무시", "OPTION1", mainTagId);
        linkTags(placeA, mainTagId, subA);
        insertStats(placeA, townId, 4.0, 0);
        insertStats(placeB, townId, 6.0, 0);
        insertStats(placeC, townId, 8.0, 0);

        List<PopularRow> rows = repository.findPopularRows(
                List.of(townId), null, List.of(subA), null, false, null, null, NO_LIMIT);

        assertThat(placeIdsOf(rows)).containsExactly(placeC, placeB, placeA);
    }

    /**
     * <b>세 EXISTS 블록이 동시에 붙는 경로.</b> 메인·서브A·서브B를 함께 주는 요청은
     * {@code appendTagFilters}가 세 조각을, {@code bindTagFilters}가 세 파라미터를 모두 맞춰야
     * 성립한다 — 바인딩이 하나라도 빠지면 결과가 아니라 <b>파라미터 미바인딩 예외</b>로 터진다.
     * 그 조합은 지금까지 어느 테스트도 밟지 않았다.
     *
     * <p>서브B만 어긋난 placeB, 서브A만 어긋난 placeC를 함께 세워 두 블록이 각각 제 몫을 하는지
     * 본다 (한쪽 블록만 있어도 통과하는 픽스처면 누락이 숨는다).
     */
    @Test
    void 서브_A와_B를_동시에_주면_세_EXISTS가_모두_적용된다() {
        long mainTagId = createMainTag("db모드메인AB");
        long subA = createSubTag("db모드서브A_AB", "OPTION1", mainTagId);
        long subB = createSubTag("db모드서브B_AB", "OPTION2", mainTagId);
        linkTags(placeA, mainTagId, subA, subB);   // 셋 다 만족 → 통과
        linkTags(placeB, mainTagId, subA);         // 서브B 불일치 → 탈락
        linkTags(placeC, mainTagId, subB);         // 서브A 불일치 → 탈락
        insertStats(placeA, townId, 4.0, 0);
        insertStats(placeB, townId, 6.0, 0);
        insertStats(placeC, townId, 8.0, 0);

        List<PopularRow> rows = repository.findPopularRows(
                List.of(townId), mainTagId, List.of(subA), List.of(subB), false, null, null, NO_LIMIT);

        assertThat(placeIdsOf(rows)).containsExactly(placeA);
    }

    /**
     * <b>이 쿼리는 활성 여부를 묻지 않는다.</b> "행이 있으면 활성"이라는 불변식을 카운트 배치가
     * 지키므로 ({@code upsertCounts}의 {@code WHERE p.active = 1} + {@code deleteStaleRows})
     * 조회는 places를 되짚지 않는다.
     * 여기서 검증하는 것은 그 <em>구조</em>다 — 가드가 몰래 되살아나면 placeC가 사라져 깨진다.
     *
     * <p>비활성화가 실제로 목록에서 사라지는 것은 카운트 배치 1회를 거친 뒤이며, 그 끝-끝 계약은
     * {@code PlaceListFlowIT.비활성화된_장소는_카운트_배치_1회_뒤_인기순에서_사라진다}가 문다.
     */
    @Test
    void 조회는_활성_여부를_묻지_않는다_불변식은_배치가_지킨다() {
        insertStats(placeA, townId, 4.0, 0);
        insertStats(placeB, townId, 2.0, 0);
        insertStats(placeC, townId, 6.0, 0);   // 배치가 아직 지우지 않은 잔행
        deactivatePlace(placeC);

        List<PopularRow> rows = findPopular(null, null, NO_LIMIT);

        assertThat(placeIdsOf(rows)).containsExactly(placeC, placeA, placeB);
    }

    // 여기 있던 재활성화_직후_ps_active가_낡아도_결과에_포함된다()는 V26과 함께 삭제했다.
    // 그 테스트는 "ps.active=0인데 places.active=1"이라는 상태를 심어야 성립하는데, V26이 컬럼
    // 자체를 drop해 그 상태를 만들 방법이 없어졌다 — 회귀를 막는 주체가 테스트에서 스키마로
    // 옮겨간 것이다. 술어를 되살리려면 마이그레이션부터 되돌려야 한다.

    /**
     * 표시 카운트는 정렬 쿼리가 함께 실어 오는 {@code ps.bookmark_count}다 (추가 조회 0).
     * 값 7은 점수(4.0)·id·행 수 어느 것과도 겹치지 않게 고른 것이다 — 겹치면 컬럼을 뒤바꾼
     * 회귀를 값으로 구분할 수 없다.
     */
    @Test
    void 표시_카운트는_ps의_bookmark_count다() {
        insertStats(placeA, townId, 4.0, 7);

        List<PopularRow> rows = findPopular(null, null, NO_LIMIT);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).placeId()).isEqualTo(placeA);
        assertThat(rows.get(0).bookmarkCount()).isEqualTo(7);
    }

    // 여기 있던 "POPULAR: 버전" 4건은 V32와 함께 삭제했다. 전부 "같은 장소가 버전마다 한 행씩
    // 존재한다"는 상태를 심어야 성립했는데, PK가 place_id 하나로 돌아오면서 그 상태를 만들 방법이
    // 없어졌다 — 회귀를 막는 주체가 테스트에서 스키마로 옮겨간 것이다(V26 때 active 축과 같은 일).
    // 장소당 행이 하나임을 직접 무는 것은 PlaceStatsRepositoryIT.같은_장소에_행은_하나뿐이다이고,
    // 배치 순간의 좌표계 갈림을 수용한 결정은 PlaceListCursor javadoc에 있다.

    /**
     * <b>행이 없는 장소는 인기순에 나오지 않는다.</b> place_stats가 기준 테이블이라 마지막 카운트
     * 배치 이후 생긴 장소가 통째로 빠진다 — 버그가 아니라 정렬을 인덱스에 흡수시킨 대가다.
     *
     * <p>제외 대상 placeD를 <b>가장 최근에 만든 장소</b>로 두는 것이 핵심이다. 기준 테이블이
     * places로 뒤집히면 D가 결과에 나타난다.
     */
    @Test
    void 행이_없는_장소는_인기순에_나오지_않는다() {
        insertStats(placeA, townId, 6.0, 0);
        insertStats(placeB, townId, 4.0, 0);
        // placeC·placeD에는 행을 만들지 않는다 (카운트 배치가 아직 닿지 않은 신규 장소)

        List<PopularRow> rows = findPopular(null, null, NO_LIMIT);

        assertThat(placeIdsOf(rows)).containsExactly(placeA, placeB);
    }

    /**
     * <b>채점 전 행은 인기순에서 통째로 빠진다.</b> 카운트 배치는 매시, 점수 배치는 새벽 1회라
     * 그 사이 생긴 장소는 {@code popular_score}가 컬럼 기본값 0에 머무는데, 그 0은 "0점"이 아니라
     * <b>"아직 점수가 없다"</b>는 뜻이다. {@code score_calculated_at IS NOT NULL}이 그것을 걸러낸다.
     *
     * <p>미채점 장소를 <b>id가 가장 작은 placeA</b>로 두는 것이 핵심이다 — 술어가 빠지면 A가 0점으로
     * 결과에 끼어들고, 점수 정렬까지 함께 무너지면 맨 앞으로 올라와 두 방향 모두 드러난다.
     */
    @Test
    void 채점_전_행은_인기순에서_제외된다() {
        insertUnscoredStats(placeA, townId, 3);
        insertStats(placeB, townId, 4.0, 0);
        insertStats(placeC, townId, 6.0, 0);

        List<PopularRow> rows = findPopular(null, null, NO_LIMIT);

        assertThat(placeIdsOf(rows)).containsExactly(placeC, placeB);
    }

    /**
     * <b>이 테스트가 술어의 존재 이유 자체다 — 미채점 0은 유효한 음수 점수보다 위에 온다.</b>
     *
     * <p>리뷰 축이 {@code w₂ × (조정평점 − C)}라 전체 평균 아래인 장소의 점수는 실제로 음수가 된다
     * (배치 IT의 {@code 평점은_전체_평균을_중심으로_가감된다}가 −0.666667을 값으로 남긴다).
     * 술어를 지우면 아직 아무 평가도 받지 않은 신규 장소(0)가 평판 나쁜 장소(−2.0)를 제치고
     * 올라가는데, 이것은 순서만 어긋나는 것이 아니라 <b>"평가가 없다"를 "평가가 보통이다"로
     * 바꿔 읽는</b> 오답이다.
     *
     * <p>앞 테스트(전부 양수)로는 이 회귀가 잡히지 않는다 — 거기서는 미채점 0이 어차피 꼴찌라
     * 술어가 빠져도 <em>맨 뒤에 하나 더 붙을</em> 뿐이고, 페이지 크기에 따라 눈에 띄지도 않는다.
     * 음수를 세워야 순서가 실제로 뒤집힌다.
     */
    @Test
    void 미채점_행은_음수_점수_장소보다_위로_올라오지_않는다() {
        insertUnscoredStats(placeA, townId, 0);       // 미채점 → popular_score DEFAULT 0
        insertStats(placeB, townId, -2.0, 0);         // 저평점이 쌓인 장소
        insertStats(placeC, townId, -0.5, 0);

        List<PopularRow> rows = findPopular(null, null, NO_LIMIT);

        // 술어가 없으면 [A(0.0), C(−0.5), B(−2.0)]가 되어 A가 1위가 된다
        assertThat(placeIdsOf(rows)).containsExactly(placeC, placeB);
        assertThat(rows.get(0).popularScore()).isEqualTo(-0.5);
    }

    /**
     * <b>음수 점수 자체는 정상값이라 걸러지지 않는다.</b> 위 테스트의 짝이다 — 술어를
     * {@code popular_score > 0} 같은 것으로 잘못 구현하면 저평점 장소가 통째로 사라진다.
     * 걸러야 하는 것은 "음수"가 아니라 "미채점"이다.
     */
    @Test
    void 음수_점수_장소는_인기순에_그대로_나온다() {
        insertStats(placeA, townId, -2.0, 0);
        insertStats(placeB, townId, 0.0, 0);          // 채점된 진짜 0점
        insertStats(placeC, townId, 1.5, 0);

        List<PopularRow> rows = findPopular(null, null, NO_LIMIT);

        assertThat(placeIdsOf(rows)).containsExactly(placeC, placeB, placeA);
    }

    // === LATEST ===

    @Test
    void LATEST는_생성일_내림차순_동점은_id_내림차순() {
        List<LatestRow> rows = findLatest(null, null, NO_LIMIT);

        // 같은 초의 b·c·d는 id 내림차순, 그보다 1분 이른 a가 마지막 — comparatorOf(LATEST)와 같은 규칙
        assertThat(latestIdsOf(rows)).containsExactly(placeD, placeC, placeB, placeA);
    }

    /**
     * 같은 초에 세 장소가 몰린 상태에서 페이지 경계가 <b>그 초 한가운데</b>를 지나게 만든다.
     *
     * <p>커서가 실어 나르는 것은 초 단위 값뿐이라({@code PlaceListCursor}의 sortKey 한계)
     * {@code p.created_at < :cursor}만으로 다음 페이지를 잡으면 커서와 같은 초에 남아 있던
     * placeB가 통째로 사라진다. 등호 분기의 id 타이브레이크가 그 구멍을 메운다.
     *
     * <p>커서 값을 상수가 아니라 <b>앞 페이지 마지막 행에서 발급부와 같은 식</b>
     * ({@code PlaceService}가 쓰는 {@code createdAt.toEpochSecond(ZoneOffset.UTC)})으로 만든다 —
     * 그래야 저장 시각과 커서 초의 왕복까지 함께 검증된다. 타임존이 개입하면 여기서 페이지가 비거나 전부 되돌아온다.
     */
    @Test
    void LATEST_커서는_초_단위_경계에서_항목을_흘리지_않는다() {
        List<LatestRow> page1 = findLatest(null, null, 2);
        assertThat(latestIdsOf(page1)).containsExactly(placeD, placeC);

        LatestRow last = page1.get(page1.size() - 1);
        long cursorSecond = last.createdAt().toEpochSecond(ZoneOffset.UTC);

        List<LatestRow> page2 = findLatest(cursorSecond, last.placeId(), 2);

        // placeB는 커서와 같은 초다 — 여기서 빠지면 조용한 누락이다
        assertThat(latestIdsOf(page2)).containsExactly(placeB, placeA);
        assertThat(latestIdsOf(page1)).doesNotContainAnyElementsOf(latestIdsOf(page2));
    }

    /**
     * LATEST의 기준 테이블은 places이고 카운트만 LEFT JOIN으로 붙는다.
     * INNER JOIN으로 바뀌면 통계가 없는 신규 장소(여기서는 b·c·d)가 최신순 맨 앞에서 사라진다.
     */
    @Test
    void LATEST_행도_bookmark_count를_place_stats에서_싣는다() {
        insertStats(placeA, townId, 4.0, 9);   // placeD에는 일부러 행을 만들지 않는다

        List<LatestRow> rows = findLatest(null, null, NO_LIMIT);

        assertThat(latestIdsOf(rows)).containsExactly(placeD, placeC, placeB, placeA);
        assertThat(latestRowOf(rows, placeA).bookmarkCount()).isEqualTo(9);
        assertThat(latestRowOf(rows, placeD).bookmarkCount()).isZero();
    }

    /**
     * <b>LATEST의 태그 경로.</b> 두 정렬은 {@code appendTagFilters}/{@code bindTagFilters}를
     * 공유하지만, 공유한다는 <em>사실</em>은 호출부가 실제로 그것을 부르고 파라미터까지 넘긴다는
     * 보장이 아니다 — LATEST 쪽 호출이 통째로 빠져도 POPULAR 테스트는 전부 그린이다.
     * 태그 필터는 정렬과 직교한 조건이므로 최신순도 같은 의미론이어야 한다.
     *
     * <p>탈락자를 <b>가장 최신인 placeD</b>로 잡는다 — 필터가 빠지면 결과 맨 앞이 D로 바뀌므로
     * 순서만 봐도 드러난다. 통과자를 둘 남겨(placeB·placeA) 필터가 붙은 뒤에도 생성일 내림차순이
     * 유지되는지 함께 본다. LATEST는 기준 테이블이 places라 place_stats를 심지 않는다.
     */
    @Test
    void LATEST에도_같은_태그_필터가_적용된다() {
        long mainTagId = createMainTag("db모드메인최신");
        long subA = createSubTag("db모드서브A최신", "OPTION1", mainTagId);
        long subB = createSubTag("db모드서브B최신", "OPTION2", mainTagId);
        linkTags(placeA, mainTagId, subA, subB);   // 가장 오래된 통과자
        linkTags(placeB, mainTagId, subA, subB);   // 통과자 중 최신
        linkTags(placeC, mainTagId, subB);         // 서브A 불일치 → 탈락
        linkTags(placeD, mainTagId, subA);         // 서브B 불일치 → 탈락 (전체 중 가장 최신)

        List<LatestRow> rows = repository.findLatestRows(
                List.of(townId), mainTagId, List.of(subA), List.of(subB), false, null, null, NO_LIMIT);

        assertThat(latestIdsOf(rows)).containsExactly(placeB, placeA);
    }

    // === 조인 순서 힌트 ===

    /**
     * 힌트는 계획만 바꾸고 결과는 못 바꾼다는 계약.
     *
     * <p>나머지 테스트는 전부 {@code regionFirstHint = false}로 부르므로, 힌트가 붙은 문장은
     * 이 셋만 실제로 실행한다. 세 EXISTS가 모두 붙는 입력을 쓰는 것이 요점이다 — 옵션 그룹이
     * 있어야 {@code QB_NAME} + {@code SEMIJOIN} 조각까지 SQL에 들어간다.
     */
    @Test
    void 인기순은_힌트_부착_여부와_무관하게_같은_결과를_낸다() {
        HintFixture fixture = givenCityScopeTagFixture();

        List<PopularRow> plain = findPopularWithTags(fixture, false, null, null);
        List<PopularRow> hinted = findPopularWithTags(fixture, true, null, null);

        // 통과자가 두 동네에 걸쳐 셋 — 비어 있으면 두 결과가 공허하게 같아진다
        assertThat(plain).hasSize(3);
        assertThat(hinted).containsExactlyElementsOf(plain);
    }

    /** 힌트는 계획만 바꾸고 결과는 못 바꾼다는 계약 — 최신순은 강제 대상 테이블이 places다 */
    @Test
    void 최신순은_힌트_부착_여부와_무관하게_같은_결과를_낸다() {
        HintFixture fixture = givenCityScopeTagFixture();

        List<LatestRow> plain = findLatestWithTags(fixture, false, null, null);
        List<LatestRow> hinted = findLatestWithTags(fixture, true, null, null);

        assertThat(plain).hasSize(3);
        assertThat(hinted).containsExactlyElementsOf(plain);
    }

    /**
     * 힌트는 계획만 바꾸고 결과는 못 바꾼다는 계약 — 커서 페이지도 같다.
     *
     * <p>커서 술어는 힌트가 지목하는 주도 테이블에 걸리는 조건이라 조인 순서가 뒤집히면 평가
     * 시점이 달라진다. 커서 + 힌트 조합은 캠페인이 계획을 재보지 않았으므로, 최소한 결과가
     * 변하지 않는다는 것만은 여기서 못 박는다.
     */
    @Test
    void 커서_페이지도_힌트_부착_여부와_무관하게_같은_결과를_낸다() {
        HintFixture fixture = givenCityScopeTagFixture();

        List<PopularRow> page1 = findPopularWithTags(fixture, false, null, null);
        PopularRow boundary = page1.get(0);

        List<PopularRow> plain =
                findPopularWithTags(fixture, false, boundary.popularScore(), boundary.placeId());
        List<PopularRow> hinted =
                findPopularWithTags(fixture, true, boundary.popularScore(), boundary.placeId());

        // 커서가 1위를 가리키므로 남는 것은 뒤의 둘이다 — 0건이면 단언이 공허해진다
        assertThat(plain).hasSize(2);
        assertThat(hinted).containsExactlyElementsOf(plain);
    }

    // === helpers ===

    /** 힌트 발동 조건(복수 동네 + 메인 태그 + 옵션 두 그룹)을 갖춘 픽스처의 좌표 */
    private record HintFixture(List<Long> townIds, long mainTagId, long subA, long subB) {}

    /**
     * 시 단위 스코프 픽스처 — 힌트는 동네가 둘 이상일 때만 붙으므로 두 번째 동네를 여기서 만든다.
     * 다른 테스트는 {@code List.of(townId)}로만 조회하므로 이 동네가 그쪽 단언에 섞이지 않는다.
     */
    private HintFixture givenCityScopeTagFixture() {
        long otherTownId = createTown();
        long placeE = createPlace("db모드E", BASE.plusMinutes(1), otherTownId);
        long placeF = createPlace("db모드F", BASE.plusMinutes(2), otherTownId);

        long mainTagId = createMainTag("db모드메인힌트");
        long subA = createSubTag("db모드서브A힌트", "OPTION1", mainTagId);
        long subB = createSubTag("db모드서브B힌트", "OPTION2", mainTagId);

        linkTags(placeA, mainTagId, subA, subB);   // 통과
        linkTags(placeB, mainTagId, subA, subB);   // 통과
        linkTags(placeC, mainTagId, subA);         // 서브B 불일치 → 탈락
        linkTags(placeE, mainTagId, subA, subB);   // 두 번째 동네의 통과자
        linkTags(placeF, mainTagId, subB);         // 서브A 불일치 → 탈락

        insertStats(placeA, townId, 4.0, 0);
        insertStats(placeB, townId, 6.0, 0);
        insertStats(placeC, townId, 8.0, 0);
        insertStats(placeE, otherTownId, 5.0, 0);
        insertStats(placeF, otherTownId, 9.0, 0);

        return new HintFixture(List.of(townId, otherTownId), mainTagId, subA, subB);
    }

    private List<PopularRow> findPopularWithTags(
            HintFixture fixture, boolean regionFirstHint, Double cursorScore, Long cursorPlaceId) {
        return repository.findPopularRows(
                fixture.townIds(), fixture.mainTagId(), List.of(fixture.subA()),
                List.of(fixture.subB()), regionFirstHint, cursorScore, cursorPlaceId, NO_LIMIT);
    }

    private List<LatestRow> findLatestWithTags(
            HintFixture fixture, boolean regionFirstHint, Long cursorSecond, Long cursorPlaceId) {
        return repository.findLatestRows(
                fixture.townIds(), fixture.mainTagId(), List.of(fixture.subA()),
                List.of(fixture.subB()), regionFirstHint, cursorSecond, cursorPlaceId, NO_LIMIT);
    }

    private List<PopularRow> findPopular(Double cursorScore, Long cursorPlaceId, int limit) {
        return repository.findPopularRows(
                List.of(townId), null, null, null, false, cursorScore, cursorPlaceId, limit);
    }

    private List<LatestRow> findLatest(Long cursorSecond, Long cursorPlaceId, int limit) {
        return repository.findLatestRows(
                List.of(townId), null, null, null, false, cursorSecond, cursorPlaceId, limit);
    }

    private List<Long> placeIdsOf(List<PopularRow> rows) {
        return rows.stream().map(PopularRow::placeId).toList();
    }

    private List<Long> latestIdsOf(List<LatestRow> rows) {
        return rows.stream().map(LatestRow::placeId).toList();
    }

    private LatestRow latestRowOf(List<LatestRow> rows, long placeId) {
        return rows.stream().filter(r -> r.placeId() == placeId).findFirst().orElseThrow();
    }

    /**
     * 행을 직접 심는다 — 정렬 쿼리는 배치 결과를 읽을 뿐이므로 배치를 돌릴 필요가 없다.
     * <b>장소당 행이 하나뿐이라</b> 같은 placeId를 두 번 심으면 중복 키로 터진다(V32).
     */
    private void insertStats(long placeId, long townId, double score, long bookmarkCount) {
        em.createNativeQuery("""
                INSERT INTO place_stats (place_id, town_id, popular_score, bookmark_count,
                                         review_count, avg_rating,
                                         count_calculated_at, score_calculated_at)
                VALUES (:placeId, :townId, :score, :cnt, 0, NULL, :calculatedAt, :calculatedAt)
                """)
                .setParameter("placeId", placeId)
                .setParameter("townId", townId)
                .setParameter("score", score)
                .setParameter("cnt", bookmarkCount)
                .setParameter("calculatedAt", COUNT_CALCULATED_AT)
                .executeUpdate();
    }

    /**
     * 카운트 배치가 만들었지만 아직 채점되지 않은 행 — {@code popular_score}는 컬럼 DEFAULT(0),
     * {@code score_calculated_at}은 NULL이다. 점수를 명시하지 않는 것이 이 헬퍼의 요점이다.
     */
    private void insertUnscoredStats(long placeId, long townId, long bookmarkCount) {
        em.createNativeQuery("""
                INSERT INTO place_stats (place_id, town_id, bookmark_count,
                                         review_count, avg_rating, count_calculated_at)
                VALUES (:placeId, :townId, :cnt, 0, NULL, :calculatedAt)
                """)
                .setParameter("placeId", placeId)
                .setParameter("townId", townId)
                .setParameter("cnt", bookmarkCount)
                .setParameter("calculatedAt", COUNT_CALCULATED_AT)
                .executeUpdate();
    }

    /**
     * 테스트마다 새 town을 만든다 — 시드·다른 IT가 남긴 장소가 결과에 섞이지 않게 하는 격리 수단이다.
     * townId가 신규 auto-increment 값이라 이 town에 속한 행은 전부 이 테스트가 만든 것이다.
     */
    private long createTown() {
        em.createNativeQuery(
                "INSERT INTO towns (name, parent_id, active) VALUES ('db모드IT동네', NULL, true)")
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM towns").getSingleResult())
                .longValue();
    }

    /** created_by는 DEFAULT 1 — V2 시드의 admin 유저(id=1)라 FK가 성립한다 (PlaceListFlowIT와 동일) */
    private long createPlace(String name, LocalDateTime createdAt) {
        return createPlace(name, createdAt, townId);
    }

    private long createPlace(String name, LocalDateTime createdAt, long placeTownId) {
        em.createNativeQuery("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (:name, 'db모드IT', :townId, true, :createdAt)
                """)
                .setParameter("name", name)
                .setParameter("townId", placeTownId)
                .setParameter("createdAt", createdAt)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM places").getSingleResult())
                .longValue();
    }

    private void deactivatePlace(long placeId) {
        em.createNativeQuery("UPDATE places SET active = false WHERE id = :id")
                .setParameter("id", placeId)
                .executeUpdate();
    }

    private long createMainTag(String name) {
        em.createNativeQuery("""
                INSERT INTO tags (name, type, parent_id, active, tag_usage)
                VALUES (:name, 'MAIN', NULL, true, 'PLACE')
                """)
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM tags").getSingleResult())
                .longValue();
    }

    /**
     * 서브 태그(OPTION1/OPTION2)를 메인 태그 아래에 만든다. parent_id를 실제 메인 태그로 채우는 것은
     * FK({@code fk_tags_parent})와 도메인 규칙(서브는 메인에 종속) 때문이며, <b>정렬 쿼리의 EXISTS는
     * type도 parent_id도 보지 않는다</b> — 태그 타입 정합은 상위(요청 검증)의 책임이라는 뜻이다.
     * 그래서 픽스처는 타입을 정확히 심어 "정상 입력에서 두 경로가 같은 답을 내는가"만 묻는다.
     */
    private long createSubTag(String name, String type, long parentId) {
        em.createNativeQuery("""
                INSERT INTO tags (name, type, parent_id, active, tag_usage)
                VALUES (:name, :type, :parentId, true, 'PLACE')
                """)
                .setParameter("name", name)
                .setParameter("type", type)
                .setParameter("parentId", parentId)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM tags").getSingleResult())
                .longValue();
    }

    private void linkTag(long placeId, long tagId) {
        em.createNativeQuery("INSERT INTO place_tag (place_id, tag_id) VALUES (:placeId, :tagId)")
                .setParameter("placeId", placeId)
                .setParameter("tagId", tagId)
                .executeUpdate();
    }

    /** 한 장소에 여러 태그를 붙인다 — 픽스처에서 "이 장소가 가진 태그 집합"이 한 줄로 읽히게 한다. */
    private void linkTags(long placeId, long... tagIds) {
        for (long tagId : tagIds) {
            linkTag(placeId, tagId);
        }
    }
}
