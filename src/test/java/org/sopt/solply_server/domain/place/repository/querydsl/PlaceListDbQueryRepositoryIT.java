package org.sopt.solply_server.domain.place.repository.querydsl;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.config.PlaceListProperties;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.CountRow;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.DistanceCandidateRow;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.LatestRow;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.PopularRow;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.RatingRow;
import org.sopt.solply_server.global.config.QueryDslConfig;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.sopt.solply_server.support.SqlStatementProbe;
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
 * LATEST (생성일 DESC, id DESC), RATING (평점 DESC, 리뷰 수 DESC, id ASC),
 * REVIEW_COUNT·BOOKMARK_COUNT (카운트 DESC, id ASC), 그리고 각각의 커서 경계. 이 규칙들은 커서를
 * <em>발급</em>하는 {@code PlaceService}와 한 쌍이라, 여기만 바뀌면 페이징이 조용히 어긋난다.
 *
 * <p>거리순은 이 파일에서 <b>후보 쿼리까지만</b> 본다 — 순서를 만드는 주체가 SQL이 아니라
 * {@code DistanceSort}라서, 여기가 지킬 계약은 "무엇이 후보인가"(좌표 없는 장소 제외·태그 필터·
 * 표시값 동승)뿐이다.
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
@Import({QueryDslConfig.class, PlaceListDbQueryRepository.class, PlaceListProperties.class})
class PlaceListDbQueryRepositoryIT extends MySqlContainerSupport {

    /**
     * 메서드 이름은 베이스의 {@code datasource}와 반드시 달라야 한다 (같으면 숨겨져 데이터소스
     * 설정이 통째로 사라진다). 이 클래스는 엔티티↔스키마 정합이 아니라 SQL 동작을 보므로
     * validate가 필요 없다.
     *
     * <p>{@code statement_inspector}는 <b>나간 문장의 원문</b>을 보기 위한 것이다 — FORCE INDEX
     * 스위치가 꺼졌을 때 문장이 힌트 도입 전과 같은지는 결과값으로는 물을 수 없다.
     */
    @DynamicPropertySource
    static void placeListDbProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
                SqlStatementProbe.class::getName);
    }

    @Autowired
    PlaceListDbQueryRepository repository;

    @Autowired
    PlaceListProperties placeListProperties;

    @Autowired
    EntityManager em;

    /** 같은 초에 몰린 장소들의 기준 시각. places.created_at이 초 정밀도 DATETIME이라 초까지만 의미 있다. */
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 7, 30, 12, 0, 0);

    /** 페이지 크기를 넘길 일이 없는 넉넉한 한도 — "정렬 결과 전체"를 뜻한다 */
    private static final int NO_LIMIT = 100;

    /** 픽스처 행의 채점 시각. 조회는 NULL 여부만 보므로 값 자체에 뜻은 없다 */
    private static final LocalDateTime SCORED_AT = BASE;

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

    /**
     * 다음 테스트가 기본 팔(자연 계획)에서 시작하도록 되돌린다 — 빈은 롤백을 따라가지 않으므로
     * 스위치를 켠 테스트가 뒤 테스트의 문장까지 바꿔 버린다 ({@code PlaceSortSnapshotIT}과 같은 이유).
     */
    @AfterEach
    void restoreForceSortIndex() {
        placeListProperties.setForceSortIndex(false);
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
    void 태그_필터는_소속_의미론을_유지한다() {
        long mainTagId = createMainTag("db모드메인태그");
        linkTag(placeA, mainTagId);   // 메인 태그를 가진 장소는 A 하나뿐
        insertStats(placeA, townId, 4.0, 0);
        insertStats(placeB, townId, 6.0, 0);
        insertStats(placeC, townId, 8.0, 0);

        List<PopularRow> rows = repository.findPopularRows(
                List.of(townId), mainTagId, null, null, null, null, NO_LIMIT);

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
                List.of(townId), mainTagId, List.of(subA1, subA2), null, null, null, NO_LIMIT);

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
                List.of(townId), mainTagId, List.of(subA), null, null, null, NO_LIMIT);

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
                List.of(townId), null, List.of(subA), null, null, null, NO_LIMIT);

        assertThat(placeIdsOf(rows)).containsExactly(placeC, placeB, placeA);
    }

    /**
     * <b>세 마스크 술어가 동시에 붙는 경로.</b> 메인·서브A·서브B를 함께 주는 요청은
     * {@code appendTagFilters}가 세 조각을, {@code bindTagFilters}가 세 파라미터를 모두 맞춰야
     * 성립한다 — 바인딩이 하나라도 빠지면 결과가 아니라 <b>파라미터 미바인딩 예외</b>로 터진다.
     *
     * <p>서브B만 어긋난 placeB, 서브A만 어긋난 placeC를 함께 세워 두 술어가 각각 제 몫을 하는지
     * 본다. <b>세 그룹을 한 마스크로 합치는 변이</b>가 여기서 드러난다 — 합치면 AND가 OR가 되어
     * 셋 다 통과한다.
     */
    @Test
    void 서브_A와_B를_동시에_주면_세_마스크_술어가_모두_적용된다() {
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
                List.of(townId), mainTagId, List.of(subA), List.of(subB), null, null, NO_LIMIT);

        assertThat(placeIdsOf(rows)).containsExactly(placeA);
    }

    /**
     * <b>이 쿼리는 활성 여부를 묻지 않는다.</b> "행이 있으면 활성"이라는 불변식을 어드민 쓰기
     * 경로가 지키므로 (행을 짓는 {@code upsertRowsForActivePlaces}의 {@code WHERE p.active = 1}과
     * 행을 지우는 {@code deleteByPlaceIds}) 조회는 places를 되짚지 않는다.
     * 여기서 검증하는 것은 그 <em>구조</em>다 — 가드가 몰래 되살아나면 placeC가 사라져 깨진다.
     *
     * <p>목록에서 실제로 사라지는 것은 어드민 삭제 경로를 거친 뒤이며, 그 끝-끝 계약은
     * {@code PlaceListFlowIT.어드민이_삭제한_장소는_배치를_기다리지_않고_인기순에서_사라진다}가 문다.
     */
    @Test
    void 조회는_활성_여부를_묻지_않는다_불변식은_쓰기_경로가_지킨다() {
        insertStats(placeA, townId, 4.0, 0);
        insertStats(placeB, townId, 2.0, 0);
        insertStats(placeC, townId, 6.0, 0);   // 어드민 경로를 지나쳐 플래그만 내려간 행
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
        insertUnscoredStatsForBasePlaces();

        List<LatestRow> rows = findLatest(null, null, NO_LIMIT);

        // 같은 초의 b·c·d는 id 내림차순, 그보다 1분 이른 a가 마지막 — comparatorOf(LATEST)와 같은 규칙
        assertThat(latestIdsOf(rows)).containsExactly(placeD, placeC, placeB, placeA);
    }

    /**
     * 같은 초에 세 장소가 몰린 상태에서 페이지 경계가 <b>그 초 한가운데</b>를 지나게 만든다.
     *
     * <p>커서가 실어 나르는 것은 초 단위 값뿐이라({@code PlaceListCursor}의 sortKey 한계)
     * {@code ps.created_at < :cursor}만으로 다음 페이지를 잡으면 커서와 같은 초에 남아 있던
     * placeB가 통째로 사라진다. 등호 분기의 id 타이브레이크가 그 구멍을 메운다.
     *
     * <p>커서 값을 상수가 아니라 <b>앞 페이지 마지막 행에서 발급부와 같은 식</b>
     * ({@code PlaceService}가 쓰는 {@code createdAt.toEpochSecond(ZoneOffset.UTC)})으로 만든다 —
     * 그래야 저장 시각과 커서 초의 왕복까지 함께 검증된다. 타임존이 개입하면 여기서 페이지가 비거나 전부 되돌아온다.
     */
    @Test
    void LATEST_커서는_초_단위_경계에서_항목을_흘리지_않는다() {
        insertUnscoredStatsForBasePlaces();

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
     * <b>LATEST의 기준 테이블도 place_stats다 (V34).</b> 행이 없는 장소는 최신순에도 나오지 않고,
     * 표시 카운트는 조인 없이 같은 행에서 그대로 실려 온다.
     *
     * <p>행을 안 만든 placeD를 <b>가장 최신 장소</b>로 두는 것이 핵심이다 — 기준 테이블이 places로
     * 되돌아가면 D가 결과 맨 앞에 나타난다.
     *
     * <p>이것이 "대가"가 아니라 "계약"인 이유는 <b>행을 만드는 쪽이 즉시</b>이기 때문이다.
     * 어드민 생성·재활성이 같은 트랜잭션에서 행을 만들므로 실제 신규 장소는 빠지지 않는다 —
     * 그 끝-끝 계약은 {@code PlaceListFlowIT}가 문다.
     */
    @Test
    void LATEST도_행이_없는_장소는_나오지_않고_카운트는_같은_행에서_온다() {
        insertStats(placeA, townId, 4.0, 9);
        insertUnscoredStats(placeB, townId, 0);
        insertUnscoredStats(placeC, townId, 0);
        // placeD에는 일부러 행을 만들지 않는다

        List<LatestRow> rows = findLatest(null, null, NO_LIMIT);

        assertThat(latestIdsOf(rows)).containsExactly(placeC, placeB, placeA);
        assertThat(latestRowOf(rows, placeA).bookmarkCount()).isEqualTo(9);
    }

    /**
     * <b>최신순은 미채점 행을 걸러내지 않는다 — 인기순과 갈리는 유일한 술어다.</b>
     * 방금 만들어진 장소는 아직 채점 전인데, 그 장소야말로 최신순 맨 앞에 와야 한다.
     * {@code score_calculated_at IS NOT NULL}이 이쪽에도 복사되면 최신순이 통째로 빈다.
     */
    @Test
    void LATEST는_채점_전_행도_보여준다() {
        insertUnscoredStatsForBasePlaces();

        assertThat(latestIdsOf(findLatest(null, null, NO_LIMIT)))
                .containsExactly(placeD, placeC, placeB, placeA);
        // 같은 픽스처가 인기순에서는 한 건도 나오지 않는다 — 비대칭이 의도임을 한 자리에서 못 박는다
        assertThat(findPopular(null, null, NO_LIMIT)).isEmpty();
    }

    /**
     * <b>LATEST의 태그 경로.</b> 두 정렬은 {@code appendTagFilters}/{@code bindTagFilters}를
     * 공유하지만, 공유한다는 <em>사실</em>은 호출부가 실제로 그것을 부르고 파라미터까지 넘긴다는
     * 보장이 아니다 — LATEST 쪽 호출이 통째로 빠져도 POPULAR 테스트는 전부 그린이다.
     * 태그 필터는 정렬과 직교한 조건이므로 최신순도 같은 의미론이어야 한다.
     *
     * <p>탈락자를 <b>가장 최신인 placeD</b>로 잡는다 — 필터가 빠지면 결과 맨 앞이 D로 바뀌므로
     * 순서만 봐도 드러난다. 통과자를 둘 남겨(placeB·placeA) 필터가 붙은 뒤에도 생성일 내림차순이
     * 유지되는지 함께 본다.
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
        insertUnscoredStatsForBasePlaces();

        List<LatestRow> rows = repository.findLatestRows(
                List.of(townId), mainTagId, List.of(subA), List.of(subB), null, null, NO_LIMIT);

        assertThat(latestIdsOf(rows)).containsExactly(placeB, placeA);
    }

    // === RATING (평점 높은 순) ===

    /**
     * 평점 DESC → 동점이면 리뷰 수 DESC → 그래도 같으면 id ASC.
     *
     * <p>2단 키가 실제로 일을 하게 세운다 — placeA·placeB·placeC의 평점이 모두 같고 리뷰 수만
     * 다르다. 리뷰 수 축이 빠지면 이 셋의 순서가 통째로 id 순(A, B, C)으로 흘러 즉시 드러난다.
     * placeD는 평점이 더 높아 리뷰 수와 무관하게 맨 앞이어야 한다 — 1단 키가 2단에 밀리는
     * 역전(리뷰 수를 먼저 보는 구현)이 여기서 잡힌다.
     */
    @Test
    void 평점_내림차순_동점은_리뷰수_내림차순_그다음_id_오름차순() {
        insertRatedStats(placeA, townId, 4.50, 3, 0);
        insertRatedStats(placeB, townId, 4.50, 9, 0);
        insertRatedStats(placeC, townId, 4.50, 3, 0);
        insertRatedStats(placeD, townId, 5.00, 1, 0);

        List<RatingRow> rows = findRating(null, null, null, NO_LIMIT);

        assertThat(ratingIdsOf(rows)).containsExactly(placeD, placeB, placeA, placeC);
    }

    /**
     * <b>seek이 세 겹인 이유를 값으로 못 박는다.</b> 경계를 "평점도 같고 리뷰 수도 같은 구간 한가운데"
     * (placeA)에 두면, 세 겹 중 어느 하나가 빠져도 결과가 달라진다 — 평점만 비교하면 placeB가
     * 되돌아오고, 리뷰 수 등호 분기를 빠뜨리면 같은 (평점, 리뷰 수)인 placeC가 통째로 누락된다.
     */
    @Test
    void 평점순_커서는_평점_리뷰수_id_세_겹으로_경계를_잡는다() {
        insertRatedStats(placeA, townId, 4.50, 3, 0);
        insertRatedStats(placeB, townId, 4.50, 9, 0);
        insertRatedStats(placeC, townId, 4.50, 3, 0);
        insertRatedStats(placeD, townId, 5.00, 1, 0);

        List<RatingRow> page2 = findRating(4.50, 3L, placeA, NO_LIMIT);

        assertThat(ratingIdsOf(page2)).containsExactly(placeC);
    }

    /**
     * <b>리뷰가 없는 장소는 0점으로 맨 뒤에 실린다 (V37).</b> 하루 전 스펙은 반대였다 —
     * {@code avg_rating IS NOT NULL}이 그 행들을 끊었고, 근거는 NULL이 커서 seek을 무너뜨린다는
     * 것이었다. 저장이 NOT NULL 0으로 바뀌면서 그 근거가 사라졌고 술어도 함께 사라졌다.
     *
     * <p>그래서 <b>커서 페이지까지 함께 문다</b>. 옛 버그의 형태가 "첫 페이지에만 보이는 장소"였으므로,
     * 첫 페이지에 있는 것만으로는 회귀를 못 잡는다.
     */
    @Test
    void 리뷰가_없는_장소는_0점으로_평점순_맨_뒤에_실린다() {
        insertRatedStats(placeA, townId, 0.00, 0, 7);   // 리뷰 0건 → 평점 0
        insertRatedStats(placeB, townId, 4.50, 3, 0);
        insertRatedStats(placeC, townId, 3.00, 1, 0);

        List<RatingRow> page1 = findRating(null, null, null, NO_LIMIT);
        List<RatingRow> page2 = findRating(4.50, 3L, placeB, NO_LIMIT);

        assertThat(ratingIdsOf(page1)).containsExactly(placeB, placeC, placeA);
        assertThat(ratingIdsOf(page2)).containsExactly(placeC, placeA);
    }

    /**
     * <b>0점 동점이 여럿이어도 커서가 그 구간을 정확히 가른다.</b> 리뷰 0건 장소가 목록에 들어오면서
     * 새로 생긴 구간이다 — 평점도 0, 리뷰 수도 0이라 앞의 두 겹이 전부 등호로 걸리고 타이브레이크가
     * 사실상 {@code place_id ASC} 하나에 걸린다.
     *
     * <p>한 칸씩 끊어 세 페이지를 걷어 중복·누락이 없음을 본다. 등호 분기가 하나라도 빠지면 여기서
     * 같은 장소가 두 번 나오거나 구간이 통째로 사라진다.
     */
    @Test
    void 평점순_커서는_0점_동점_구간도_중복_누락_없이_가른다() {
        insertRatedStats(placeA, townId, 0.00, 0, 0);
        insertRatedStats(placeB, townId, 0.00, 0, 0);
        insertRatedStats(placeC, townId, 0.00, 0, 0);
        insertRatedStats(placeD, townId, 4.00, 2, 0);

        List<RatingRow> page1 = findRating(null, null, null, 1);
        List<RatingRow> page2 = findRating(4.00, 2L, placeD, 1);
        List<RatingRow> page3 = findRating(0.00, 0L, placeA, 1);
        List<RatingRow> page4 = findRating(0.00, 0L, placeB, NO_LIMIT);

        assertThat(ratingIdsOf(page1)).containsExactly(placeD);
        assertThat(ratingIdsOf(page2)).containsExactly(placeA);
        assertThat(ratingIdsOf(page3)).containsExactly(placeB);
        assertThat(ratingIdsOf(page4)).containsExactly(placeC);
    }

    /** 평점순도 표시값(북마크 수)을 같은 행에서 실어 온다 — 추가 조회가 없다는 계약 */
    @Test
    void 평점순도_표시_카운트를_같은_행에서_싣는다() {
        insertRatedStats(placeA, townId, 4.50, 3, 7);

        List<RatingRow> rows = findRating(null, null, null, NO_LIMIT);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).bookmarkCount()).isEqualTo(7);
        assertThat(rows.get(0).reviewCount()).isEqualTo(3);
        assertThat(rows.get(0).avgRating()).isEqualByComparingTo("4.50");
    }

    /** 평점순에도 태그 필터가 그대로 붙는다 — 정렬과 직교한 조건이다 */
    @Test
    void 평점순에도_같은_태그_필터가_적용된다() {
        long mainTagId = createMainTag("db모드메인평점");
        linkTag(placeA, mainTagId);
        insertRatedStats(placeA, townId, 3.00, 1, 0);
        insertRatedStats(placeB, townId, 5.00, 9, 0);   // 평점 1위지만 태그가 없다 → 탈락

        List<RatingRow> rows = repository.findRatingRows(
                List.of(townId), mainTagId, null, null, null, null, null, NO_LIMIT);

        assertThat(ratingIdsOf(rows)).containsExactly(placeA);
    }

    // === REVIEW_COUNT / BOOKMARK_COUNT (카운트 축) ===

    /**
     * 리뷰 수 DESC, 동점은 id ASC. 동점을 <b>가장 큰 카운트</b>에 두어(placeA·placeB) 타이브레이크가
     * 1위 자리에서 판정되게 한다 — id DESC로 잘못 짜면 첫 행부터 어긋난다.
     */
    @Test
    void 리뷰순은_리뷰수_내림차순_동점은_id_오름차순() {
        insertRatedStats(placeA, townId, 4.00, 9, 0);
        insertRatedStats(placeB, townId, 4.00, 9, 0);
        insertRatedStats(placeC, townId, 4.00, 5, 0);
        insertRatedStats(placeD, townId, 0.00, 0, 0);   // 리뷰 0건도 결과에는 남는다

        List<CountRow> rows = repository.findReviewCountRows(
                List.of(townId), null, null, null, null, null, NO_LIMIT);

        assertThat(countIdsOf(rows)).containsExactly(placeA, placeB, placeC, placeD);
    }

    /** 경계를 동점 구간 한가운데(placeA)에 둔다 — 등호 분기가 빠지면 동점인 placeB가 누락된다 */
    @Test
    void 리뷰순_커서는_카운트_미만_또는_동점_id_초과다() {
        insertRatedStats(placeA, townId, 4.00, 9, 0);
        insertRatedStats(placeB, townId, 4.00, 9, 0);
        insertRatedStats(placeC, townId, 4.00, 5, 0);

        List<CountRow> rows = repository.findReviewCountRows(
                List.of(townId), null, null, null, 9L, placeA, NO_LIMIT);

        assertThat(countIdsOf(rows)).containsExactly(placeB, placeC);
    }

    /**
     * 북마크 수 DESC, 동점은 id ASC. <b>인기순과 다른 축</b>임을 픽스처가 직접 보인다 —
     * 인기 점수는 placeC가 가장 높지만 북마크 수로는 꼴찌라, 두 정렬이 같은 SQL을 쓰면 순서가 뒤집힌다.
     */
    @Test
    void 북마크순은_북마크수_내림차순이고_인기순과_다른_축이다() {
        insertStats(placeA, townId, 1.0, 9);
        insertStats(placeB, townId, 2.0, 9);
        insertStats(placeC, townId, 9.0, 1);

        List<CountRow> byBookmark = repository.findBookmarkCountRows(
                List.of(townId), null, null, null, null, null, NO_LIMIT);

        assertThat(countIdsOf(byBookmark)).containsExactly(placeA, placeB, placeC);
        assertThat(placeIdsOf(findPopular(null, null, NO_LIMIT)))
                .containsExactly(placeC, placeB, placeA);
    }

    @Test
    void 북마크순_커서는_카운트_미만_또는_동점_id_초과다() {
        insertStats(placeA, townId, 1.0, 9);
        insertStats(placeB, townId, 2.0, 9);
        insertStats(placeC, townId, 9.0, 1);

        List<CountRow> rows = repository.findBookmarkCountRows(
                List.of(townId), null, null, null, 9L, placeA, NO_LIMIT);

        assertThat(countIdsOf(rows)).containsExactly(placeB, placeC);
    }

    /**
     * 카운트 축 두 정렬은 한 문장을 공유하지만 <b>정렬 컬럼만</b>이 다르다. 같은 픽스처에서 두 순서가
     * 갈리는지 한 자리에서 본다 — 컬럼 이름을 잘못 넘기면(둘 다 review_count 등) 여기서 걸린다.
     */
    @Test
    void 리뷰순과_북마크순은_서로_다른_컬럼으로_정렬한다() {
        insertRatedStats(placeA, townId, 4.00, 9, 1);
        insertRatedStats(placeB, townId, 4.00, 1, 9);

        assertThat(countIdsOf(repository.findReviewCountRows(
                List.of(townId), null, null, null, null, null, NO_LIMIT)))
                .containsExactly(placeA, placeB);
        assertThat(countIdsOf(repository.findBookmarkCountRows(
                List.of(townId), null, null, null, null, null, NO_LIMIT)))
                .containsExactly(placeB, placeA);
    }

    // === DISTANCE 후보 ===

    /**
     * <b>좌표가 없는 장소는 후보에서 빠진다.</b> 거리를 잴 수 없는 장소를 뒤에 붙이면 커서 경계에서
     * 조용히 사라지므로 WHERE에서 끊는다.
     *
     * <p>표시값이 같은 행에서 실려 오는지도 함께 본다 — 정렬만 앱으로 옮겼을 뿐 "표시값을 위한
     * 추가 조회가 없다"는 성질은 다른 정렬과 같아야 한다.
     */
    @Test
    void 거리순_후보는_좌표가_없는_장소를_제외하고_표시값을_함께_싣는다() {
        insertRatedStats(placeA, townId, 4.50, 3, 7);
        insertRatedStats(placeB, townId, 0.00, 0, 0);
        insertRatedStats(placeC, townId, 4.00, 1, 0);
        setCoordinates(placeA, 37.5665, 126.9780);
        setCoordinates(placeB, 37.5, null);        // 경도만 없어도 후보가 아니다
        // placeC는 두 값 모두 NULL 그대로

        List<DistanceCandidateRow> rows = repository.findDistanceCandidates(
                List.of(townId), null, null, null);

        assertThat(rows).hasSize(1);
        DistanceCandidateRow row = rows.get(0);
        assertThat(row.placeId()).isEqualTo(placeA);
        assertThat(row.latitude()).isEqualTo(37.5665);
        assertThat(row.longitude()).isEqualTo(126.9780);
        assertThat(row.bookmarkCount()).isEqualTo(7);
        assertThat(row.reviewCount()).isEqualTo(3);
        assertThat(row.avgRating()).isEqualByComparingTo("4.50");
    }

    /**
     * 거리순 후보에도 <b>같은 태그 필터</b>가 붙는다. 이 경로만 places와 조인하므로 태그 술어를
     * 통째로 빠뜨리기 쉬운 자리다 — 빠지면 결과가 2건이 된다.
     */
    @Test
    void 거리순_후보에도_같은_태그_필터가_적용된다() {
        long mainTagId = createMainTag("db모드메인거리");
        linkTag(placeA, mainTagId);
        insertRatedStats(placeA, townId, 0.00, 0, 0);
        insertRatedStats(placeB, townId, 0.00, 0, 0);
        setCoordinates(placeA, 37.1, 127.1);
        setCoordinates(placeB, 37.2, 127.2);

        List<DistanceCandidateRow> rows = repository.findDistanceCandidates(
                List.of(townId), mainTagId, null, null);

        assertThat(rows.stream().map(DistanceCandidateRow::placeId).toList())
                .containsExactly(placeA);
    }

    // === FORCE INDEX 스위치 (벤치의 A강제 팔) ===

    /**
     * <b>스위치를 켜도 정렬 3종의 결과가 한 행도 달라지지 않는다.</b> 팔 교대 측정의 전제 조건이다 —
     * 여기가 빨간 상태로 낸 수치는 서로 다른 답을 낸 두 계획의 비용을 비교한 것이라 뜻이 없다
     * ({@code PlaceSortSnapshotIT}의 모드 등가 게이트와 같은 역할·같은 근거).
     *
     * <p><b>기대값을 손으로 적지 않는다</b> — 두 모드를 실제로 돌려 서로 대조한다. 순서 자체의
     * 정본은 위쪽 정렬 테스트들이 값으로 물고 있다.
     *
     * <p>두 페이지를 도는 이유: 커서 술어가 붙은 문장은 WHERE 형상이 달라 <b>계획이 갈리는 지점</b>이고
     * (무힌트에서 2페이지만 의도 인덱스로 넘어가는 칸이 있었다), 그 두 문장이 같은 답을 내는지는
     * 1페이지만 봐서는 알 수 없다.
     */
    @Test
    void 강제_인덱스_스위치는_단일_동네_정렬_3종의_결과를_바꾸지_않는다() {
        givenMixedAxisFixture();

        assertForceIndexAgrees("단일 동네", List.of(townId));
    }

    /**
     * <b>다중 동네도 같다.</b> town이 여럿이면 인덱스가 전역 순서를 만들지 못해 어느 팔에서도
     * filesort가 남는데(무힌트·강제가 같은 형상이라는 것이 반사실 대조의 결론이었다), 그 경로에서도
     * 답이 같은지를 따로 문다 — 단일 동네만 보면 힌트가 실제로 계획을 바꾸는 유일한 형상에서만
     * 등가를 확인한 셈이 된다.
     */
    @Test
    void 강제_인덱스_스위치는_다중_동네에서도_결과를_바꾸지_않는다() {
        long otherTownId = createTown();
        long placeE = createPlace("db모드E강제", BASE.plusMinutes(1), otherTownId);
        givenMixedAxisFixture();
        insertRatedStats(placeE, otherTownId, 4.50, 9, 4);   // 세 축 모두에서 동점 상대가 된다

        assertForceIndexAgrees("다중 동네", List.of(townId, otherTownId));
    }

    /**
     * <b>스위치가 꺼져 있으면 문장이 힌트 도입 전과 바이트째 같고, 켜지면 FROM의 테이블 참조
     * 직후에만 힌트가 붙는다.</b>
     *
     * <p>앞 두 테스트(결과 등가)로는 이것을 물을 수 없다 — 꺼진 팔의 문장이 조금 달라져도 답은
     * 같으므로 그린이다. 그런데 A자연 팔의 존재 이유가 "스위치를 들이기 전과 같은 문장"이라,
     * 문장이 달라지면 캠페인의 기준선이 통째로 흔들린다.
     *
     * <p>대조 방식이 요점이다. 켠 문장을 손으로 적어 두면 SELECT 목록이나 개행이 바뀔 때마다
     * 테스트가 깨질 뿐 계약을 못 지키므로, <b>꺼진 문장에서 딱 그 한 조각만 끼워 넣은 것과 같은지</b>를
     * 묻는다 — 힌트의 위치(별칭 뒤·WHERE 앞)와 "그 밖에는 아무것도 안 바뀐다"가 한 단언에 들어온다.
     */
    @Test
    void 스위치가_꺼지면_문장은_그대로이고_켜지면_FROM_뒤에만_힌트가_붙는다() {
        insertRatedStats(placeA, townId, 4.50, 3, 7);

        assertHintOnlyInFrom("idx_place_stats_town_rating",
                () -> findRating(null, null, null, NO_LIMIT));
        assertHintOnlyInFrom("idx_place_stats_town_reviews",
                () -> repository.findReviewCountRows(
                        List.of(townId), null, null, null, null, null, NO_LIMIT));
        assertHintOnlyInFrom("idx_place_stats_town_bookmarks",
                () -> repository.findBookmarkCountRows(
                        List.of(townId), null, null, null, null, null, NO_LIMIT));
    }

    /**
     * <b>인기·최신·거리는 스위치를 켜도 문장이 변하지 않는다.</b> 셋은 이미 의도한 계획으로만 돌아
     * 힌트를 들일 이유가 없다 — 자기 인덱스에만 있는 컬럼을 SELECT해 나머지 인덱스가 커버링에서
     * 탈락하기 때문이고(인기·최신), 거리순은 정렬을 DB에 맡기지 않는다.
     *
     * <p>스위치를 켠 채로 세 문장을 모두 잡아 힌트가 <b>한 글자도</b> 새지 않았는지 본다. 공용
     * FROM 헬퍼를 세 경로에 무심코 확대하는 변경이 여기서 걸린다.
     */
    @Test
    void 인기_최신_거리는_스위치를_켜도_힌트가_붙지_않는다() {
        insertStats(placeA, townId, 4.0, 7);
        setCoordinates(placeA, 37.5665, 126.9780);

        placeListProperties.setForceSortIndex(true);

        assertThat(captureListSql(() -> findPopular(null, null, NO_LIMIT)))
                .as("인기순").doesNotContain("FORCE INDEX");
        assertThat(captureListSql(() -> findLatest(null, null, NO_LIMIT)))
                .as("최신순").doesNotContain("FORCE INDEX");
        assertThat(captureListSql(
                () -> repository.findDistanceCandidates(List.of(townId), null, null, null)))
                .as("거리순 후보").doesNotContain("FORCE INDEX");
    }

    // === 다중 동네 형상 ===

    /**
     * <b>안쪽 LIMIT이 바깥 LIMIT과 같아야 전역 상위 N이 온전하다.</b> 상위 세 건을 한 동네에
     * 몰아 두면 안쪽 LIMIT을 동네 수로 나누거나 1로 잡는 실수가 여기서 결과를 잃는다 — 다른
     * 동네가 아무리 낮은 점수를 올려도 상위 N이 한 동네에서 전부 나올 수 있어야 한다.
     */
    @Test
    void 동네별_LIMIT은_한_동네에_몰린_상위_N을_잃지_않는다() {
        long otherTownId = createTown();
        long placeE = createPlace("db모드E", BASE, otherTownId);
        long placeF = createPlace("db모드F", BASE, otherTownId);

        insertStats(placeA, townId, 9.0, 0);
        insertStats(placeB, townId, 8.0, 0);
        insertStats(placeC, townId, 7.0, 0);
        insertStats(placeE, otherTownId, 1.0, 0);
        insertStats(placeF, otherTownId, 0.5, 0);

        assertThat(placeIdsOf(repository.findPopularRows(
                List.of(townId, otherTownId), null, null, null, null, null, 3)))
                .containsExactly(placeA, placeB, placeC);
    }

    /**
     * <b>동네 경계를 넘는 커서 페이징 — 최신순.</b> 동네별 조각은 각자 자기 동네의 상위 N만
     * 올리므로, 커서 술어가 조각 <em>안</em>으로 들어가지 않으면 조각이 커서 앞의 행으로 LIMIT을
     * 채우고 다음 페이지에 실려야 할 행이 조용히 사라진다.
     *
     * <p>대조는 "페이지를 이어붙인 것 = 한 번에 받은 전량"이다. 기대 순서를 손으로 적으면 정렬
     * 규칙이 바뀔 때마다 깨질 뿐 경계에서 흘리는 행을 못 잡는다.
     *
     * <p>최신순을 고른 것은 <b>타이브레이크가 홀로 id 내림차순</b>이라서다 — 바깥 정렬을 다른 넷과
     * 같은 방향으로 적는 실수가 있으면 같은 초의 장소들이 동네 경계에서 어긋난다.
     */
    @Test
    void 동네_경계를_넘는_최신순_커서가_행을_흘리지_않는다() {
        long otherTownId = createTown();
        // 같은 초를 두 동네에 걸쳐 심는다 — 타이브레이크가 동네 경계에서 도는 자리다
        long placeE = createPlace("db모드E", BASE, otherTownId);
        long placeF = createPlace("db모드F", BASE, otherTownId);

        insertStats(placeA, townId, 1.0, 0);
        insertStats(placeB, townId, 2.0, 0);
        insertStats(placeC, townId, 3.0, 0);
        insertStats(placeD, townId, 4.0, 0);
        insertStats(placeE, otherTownId, 5.0, 0);
        insertStats(placeF, otherTownId, 6.0, 0);

        List<Long> towns = List.of(townId, otherTownId);
        List<Long> whole = latestIdsOf(
                repository.findLatestRows(towns, null, null, null, null, null, NO_LIMIT));

        List<Long> paged = new ArrayList<>();
        Long cursorSecond = null;
        Long cursorPlaceId = null;
        for (int page = 0; page < whole.size() + 1; page++) {
            List<LatestRow> rows =
                    repository.findLatestRows(towns, null, null, null, cursorSecond, cursorPlaceId, 2);
            if (rows.isEmpty()) {
                break;
            }
            paged.addAll(latestIdsOf(rows));
            LatestRow last = rows.get(rows.size() - 1);
            // 발급부와 같은 식으로 만든다 — 상수로 적으면 epoch 초 왕복이 검증에서 빠진다
            cursorSecond = last.createdAt().toEpochSecond(ZoneOffset.UTC);
            cursorPlaceId = last.placeId();
        }

        assertThat(whole).as("전량 (비어 있으면 대조가 공허하다)").hasSize(6);
        assertThat(paged).as("2건씩 이어받은 결과").isEqualTo(whole);
    }

    /**
     * <b>동네 경계를 넘는 커서 페이징 — 평점순.</b> 커서 키가 둘(평점·리뷰 수)이라 동네별 조각
     * 안에서 세 겹 seek이 돌아야 하고, 완전 동점을 두 동네에 걸쳐 심어 그 경계가 조각을
     * 가로지르게 했다.
     */
    @Test
    void 동네_경계를_넘는_평점순_커서가_행을_흘리지_않는다() {
        long otherTownId = createTown();
        long placeE = createPlace("db모드E", BASE, otherTownId);
        long placeF = createPlace("db모드F", BASE, otherTownId);

        insertRatedStats(placeA, townId, 4.50, 3, 0);
        insertRatedStats(placeB, townId, 4.50, 3, 0);       // A와 완전 동점 → id로 갈림
        insertRatedStats(placeC, townId, 4.50, 1, 0);       // 평점만 동점 → 리뷰 수로 갈림
        insertRatedStats(placeD, townId, 3.00, 5, 0);
        insertRatedStats(placeE, otherTownId, 4.50, 3, 0);  // 동점이 동네를 넘는다
        insertRatedStats(placeF, otherTownId, 5.00, 0, 0);

        List<Long> towns = List.of(townId, otherTownId);
        List<Long> whole = ratingIdsOf(
                repository.findRatingRows(towns, null, null, null, null, null, null, NO_LIMIT));

        List<Long> paged = new ArrayList<>();
        Double cursorRating = null;
        Long cursorReviewCount = null;
        Long cursorPlaceId = null;
        for (int page = 0; page < whole.size() + 1; page++) {
            List<RatingRow> rows = repository.findRatingRows(
                    towns, null, null, null, cursorRating, cursorReviewCount, cursorPlaceId, 2);
            if (rows.isEmpty()) {
                break;
            }
            paged.addAll(ratingIdsOf(rows));
            RatingRow last = rows.get(rows.size() - 1);
            cursorRating = last.avgRating().doubleValue();
            cursorReviewCount = last.reviewCount();
            cursorPlaceId = last.placeId();
        }

        assertThat(whole).as("전량 (비어 있으면 대조가 공허하다)").hasSize(6);
        assertThat(paged).as("2건씩 이어받은 결과").isEqualTo(whole);
    }

    /**
     * <b>동네가 여럿이면 JSON_TABLE + LATERAL, 하나면 그 감싸개가 없다.</b>
     *
     * <p>앞 세 테스트(결과 등가)로는 이것을 물을 수 없다 — {@code IN} 한 문장으로 되돌려도 답은
     * 같으므로 그린이다. 그런데 이 형상을 고른 이유가 "동네별 조각이 자기 동네에서 일찍 멈춘다"라,
     * 문장이 합쳐지면 채택 근거가 통째로 사라진다. 안쪽 LIMIT이 빠지는 회귀도 여기서 걸린다
     * (동네별 조기 종료가 사라진다).
     */
    @Test
    void 동네가_여럿이면_LATERAL로_감싸고_하나면_감싸지_않는다() {
        long otherTownId = createTown();
        insertStats(placeA, townId, 9.0, 0);

        String single = captureListSql(() -> repository.findPopularRows(
                List.of(townId), null, null, null, null, null, NO_LIMIT));
        String multi = captureListSql(() -> repository.findPopularRows(
                List.of(townId, otherTownId), null, null, null, null, null, NO_LIMIT));

        assertThat(single).as("단일 동네").doesNotContain("JSON_TABLE", "LATERAL");
        assertThat(countOf(single, "WHERE ps.town_id = ?")).as("단일 동네의 동네 조건").isEqualTo(1);
        assertThat(countOf(single, "LIMIT ?")).as("단일 동네의 LIMIT").isEqualTo(1);

        assertThat(multi).as("다중 동네").contains("JSON_TABLE(CAST(? AS JSON)", "JOIN LATERAL (");
        assertThat(countOf(multi, "WHERE ps.town_id = towns.town_id")).as("동네 조건").isEqualTo(1);
        // 안쪽 하나 + 합친 뒤 하나. 안쪽 LIMIT이 빠지면 동네별 조기 종료가 사라진다
        assertThat(countOf(multi, "LIMIT ?")).as("LIMIT").isEqualTo(2);
    }

    /**
     * <b>문장 텍스트가 동네 수와 무관하다 — 이 형상을 고른 이유 그 자체다 (#394).</b> 동네마다
     * SELECT 한 벌을 복제하던 앞 형상은 문장 길이가 동네 수에 정비례해 드라이버 재파싱·패킷 조립
     * 비용을 키웠다. 동네 목록이 <b>바인드 값 하나</b>로 들어오는 한 2개든 5개든 같은 문장이다.
     *
     * <p>길이가 아니라 <b>문자열 동일성</b>을 묻는다 — 길이만 보면 동네 id 자릿수가 문장에 새는
     * 회귀(리터럴 인라인)를 놓친다.
     */
    @Test
    void 다중_동네_문장은_동네_수가_늘어도_같다() {
        long town2 = createTown();
        long town3 = createTown();
        long town4 = createTown();
        long town5 = createTown();
        insertStats(placeA, townId, 9.0, 0);

        String twoTowns = captureListSql(() -> repository.findPopularRows(
                List.of(townId, town2), null, null, null, null, null, NO_LIMIT));
        String fiveTowns = captureListSql(() -> repository.findPopularRows(
                List.of(townId, town2, town3, town4, town5), null, null, null, null, null,
                NO_LIMIT));

        assertThat(fiveTowns).isEqualTo(twoTowns);
    }

    // === 마스크 술어 ↔ EXISTS 동치 ===

    /**
     * <b>V34 전환의 성공 조건은 "결과가 한 행도 달라지지 않는다"였다</b>(캠페인
     * {@code 2026-08-11_tag-bitmask-read-model} S1). 앞의 테스트들이 <em>기대값</em>을 손으로 적어
     * 두는 방식이라면, 여기는 걷어낸 {@code place_tag} EXISTS를 이 파일 안에 그대로 남겨 두고
     * <b>같은 픽스처에 두 문장을 나란히 돌려 대조</b>한다 — 손으로 적은 기대값이 함께 틀리는
     * 경우까지 걸러 낸다.
     *
     * <p>덮는 태그 형상 넷: 메인 단독(넓게 걸림) · 세 그룹 조합(핫패스) · 희귀(통과자 1) ·
     * 0건(아무도 안 가진 태그). 마지막 둘이 중요한 이유는 마스크 술어가 <b>조기 종료를 잃는</b>
     * 구간이기 때문이다 — 성능은 캠페인이 재고, 여기서는 그 구간에서도 <em>답이 같은지</em>만 문다.
     */
    @Test
    void 마스크_필터는_EXISTS와_같은_집합을_낸다() {
        TagFixture f = givenTwoTownTagFixture();

        assertSameAsExists(f, f.mainTagId(), null, null);
        assertSameAsExists(f, f.mainTagId(), List.of(f.subA()), List.of(f.subB()));
        assertSameAsExists(f, f.mainTagId(), List.of(f.rareTag()), null);
        assertSameAsExists(f, f.mainTagId(), List.of(f.orphanTag()), null);
    }

    /**
     * <b>커서 두 번째 페이지도 같아야 한다.</b> 커서 술어와 태그 술어가 한 WHERE 안에서 만나는
     * 조합이고, 직전 캠페인이 계획을 재보지 않고 남겨 둔 구멍이 정확히 여기였다.
     *
     * <p>경계를 1페이지 마지막 행에서 <b>발급부와 같은 식</b>으로 만든다 — 상수로 적으면 커서
     * 왕복(점수의 double 좁힘, 생성일의 epoch 초 변환)이 검증에서 빠진다.
     */
    @Test
    void 커서_두번째_페이지도_EXISTS와_같은_집합을_낸다() {
        TagFixture f = givenTwoTownTagFixture();
        List<Long> subA = List.of(f.subA());
        List<Long> subB = List.of(f.subB());

        PopularRow popularBoundary = repository.findPopularRows(
                f.townIds(), f.mainTagId(), subA, subB, null, null, 2).get(1);
        LatestRow latestBoundary = repository.findLatestRows(
                f.townIds(), f.mainTagId(), subA, subB, null, null, 2).get(1);

        List<PopularRow> popularPage2 = repository.findPopularRows(
                f.townIds(), f.mainTagId(), subA, subB,
                popularBoundary.popularScore(), popularBoundary.placeId(), NO_LIMIT);
        List<LatestRow> latestPage2 = repository.findLatestRows(
                f.townIds(), f.mainTagId(), subA, subB,
                latestBoundary.createdAt().toEpochSecond(ZoneOffset.UTC),
                latestBoundary.placeId(), NO_LIMIT);

        // 통과자가 셋이라 2페이지에 정확히 하나가 남는다 — 0건이면 대조가 공허해진다
        assertThat(popularPage2).hasSize(1);
        assertThat(latestPage2).hasSize(1);
        assertThat(placeIdsOf(popularPage2)).isEqualTo(popularIdsByExists(
                f, f.mainTagId(), subA, subB,
                popularBoundary.popularScore(), popularBoundary.placeId()));
        assertThat(latestIdsOf(latestPage2)).isEqualTo(latestIdsByExists(
                f, f.mainTagId(), subA, subB,
                latestBoundary.createdAt(), latestBoundary.placeId()));
    }

    // === helpers ===

    /**
     * 두 동네에 걸친 태그 픽스처. 동네를 둘로 두는 것은 {@code town_id IN (...)}이 여러 개인
     * 실제 시 단위 조회 형상을 밟기 위해서다 — 다른 테스트는 {@code List.of(townId)}로만
     * 조회하므로 두 번째 동네가 그쪽 단언에 섞이지 않는다.
     */
    private record TagFixture(List<Long> townIds, long mainTagId, long subA, long subB,
                              long rareTag, long orphanTag) {}

    private TagFixture givenTwoTownTagFixture() {
        long otherTownId = createTown();
        long placeE = createPlace("db모드E", BASE.plusMinutes(1), otherTownId);
        long placeF = createPlace("db모드F", BASE.plusMinutes(2), otherTownId);

        long mainTagId = createMainTag("db모드메인동치");
        long subA = createSubTag("db모드서브A동치", "OPTION1", mainTagId);
        long subB = createSubTag("db모드서브B동치", "OPTION2", mainTagId);
        long rareTag = createSubTag("db모드희귀동치", "OPTION1", mainTagId);
        long orphanTag = createSubTag("db모드0건동치", "OPTION1", mainTagId);

        linkTags(placeA, mainTagId, subA, subB, rareTag);   // 통과 + 희귀 태그의 유일한 주인
        linkTags(placeB, mainTagId, subA, subB);            // 통과
        linkTags(placeC, mainTagId, subA);                  // 서브B 불일치 → 탈락
        linkTags(placeE, mainTagId, subA, subB);            // 두 번째 동네의 통과자
        linkTags(placeF, mainTagId, subB);                  // 서브A 불일치 → 탈락
        // orphanTag는 아무 장소에도 붙이지 않는다

        insertStats(placeA, townId, 4.0, 0);
        insertStats(placeB, townId, 6.0, 0);
        insertStats(placeC, townId, 8.0, 0);
        insertStats(placeE, otherTownId, 5.0, 0);
        insertStats(placeF, otherTownId, 9.0, 0);

        return new TagFixture(
                List.of(townId, otherTownId), mainTagId, subA, subB, rareTag, orphanTag);
    }

    /** 두 정렬 모두에서 마스크 술어와 EXISTS의 결과가 <b>순서까지</b> 같은지 본다. */
    private void assertSameAsExists(
            TagFixture f, Long mainTagId, List<Long> subA, List<Long> subB) {
        assertThat(placeIdsOf(repository.findPopularRows(
                f.townIds(), mainTagId, subA, subB, null, null, NO_LIMIT)))
                .isEqualTo(popularIdsByExists(f, mainTagId, subA, subB, null, null));
        assertThat(latestIdsOf(repository.findLatestRows(
                f.townIds(), mainTagId, subA, subB, null, null, NO_LIMIT)))
                .isEqualTo(latestIdsByExists(f, mainTagId, subA, subB, null, null));
    }

    /**
     * V34 이전의 태그 술어를 그대로 되살린 대조군. <b>기준 테이블·정렬·커서는 현행과 같게 두고
     * 태그 조건만 EXISTS로 되돌린다</b> — 최신순의 기준 테이블 전환(places → place_stats)은
     * 의도된 계약 변경이라 대조 대상이 아니고, 여기서 묻는 것은 오직 "태그 필터의 의미론이
     * 같은가"다. 둘을 한꺼번에 바꿔 비교하면 어느 쪽이 결과를 바꿨는지 가를 수 없다.
     */
    private List<Long> popularIdsByExists(
            TagFixture f, Long mainTagId, List<Long> subA, List<Long> subB,
            Double cursorScore, Long cursorPlaceId) {
        StringBuilder sql = new StringBuilder("""
                SELECT ps.place_id
                FROM place_stats ps
                WHERE ps.town_id IN (:townIds)
                  AND ps.score_calculated_at IS NOT NULL
                """);
        appendExistsFilters(sql, mainTagId, subA, subB);
        if (cursorScore != null) {
            sql.append("""
                      AND (ps.popular_score < :cursorScore
                           OR (ps.popular_score = :cursorScore AND ps.place_id > :cursorPlaceId))
                    """);
        }
        sql.append("ORDER BY ps.popular_score DESC, ps.place_id ASC");

        Query query = em.createNativeQuery(sql.toString())
                .setParameter("townIds", f.townIds());
        bindExistsFilters(query, mainTagId, subA, subB);
        if (cursorScore != null) {
            query.setParameter("cursorScore", cursorScore);
            query.setParameter("cursorPlaceId", cursorPlaceId);
        }
        return toIds(query);
    }

    /** {@link #popularIdsByExists}의 최신순 짝. 대조 범위에 대한 설명은 그쪽에 있다. */
    private List<Long> latestIdsByExists(
            TagFixture f, Long mainTagId, List<Long> subA, List<Long> subB,
            LocalDateTime cursorCreatedAt, Long cursorPlaceId) {
        StringBuilder sql = new StringBuilder("""
                SELECT ps.place_id
                FROM place_stats ps
                WHERE ps.town_id IN (:townIds)
                """);
        appendExistsFilters(sql, mainTagId, subA, subB);
        if (cursorCreatedAt != null) {
            sql.append("""
                      AND (ps.created_at < :cursorCreatedAt
                           OR (ps.created_at = :cursorCreatedAt AND ps.place_id < :cursorPlaceId))
                    """);
        }
        sql.append("ORDER BY ps.created_at DESC, ps.place_id DESC");

        Query query = em.createNativeQuery(sql.toString())
                .setParameter("townIds", f.townIds());
        bindExistsFilters(query, mainTagId, subA, subB);
        if (cursorCreatedAt != null) {
            query.setParameter("cursorCreatedAt", cursorCreatedAt);
            query.setParameter("cursorPlaceId", cursorPlaceId);
        }
        return toIds(query);
    }

    private void appendExistsFilters(
            StringBuilder sql, Long mainTagId, List<Long> subA, List<Long> subB) {
        if (mainTagId != null) {
            sql.append("""
                      AND EXISTS (SELECT 1 FROM place_tag pt
                                   WHERE pt.place_id = ps.place_id AND pt.tag_id = :mainTagId)
                    """);
        }
        if (mainTagId != null && subA != null && !subA.isEmpty()) {
            sql.append("""
                      AND EXISTS (SELECT 1 FROM place_tag pt
                                   WHERE pt.place_id = ps.place_id AND pt.tag_id IN (:subTagAIds))
                    """);
        }
        if (mainTagId != null && subB != null && !subB.isEmpty()) {
            sql.append("""
                      AND EXISTS (SELECT 1 FROM place_tag pt
                                   WHERE pt.place_id = ps.place_id AND pt.tag_id IN (:subTagBIds))
                    """);
        }
    }

    private void bindExistsFilters(
            Query query, Long mainTagId, List<Long> subA, List<Long> subB) {
        if (mainTagId != null) {
            query.setParameter("mainTagId", mainTagId);
        }
        if (mainTagId != null && subA != null && !subA.isEmpty()) {
            query.setParameter("subTagAIds", subA);
        }
        if (mainTagId != null && subB != null && !subB.isEmpty()) {
            query.setParameter("subTagBIds", subB);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> toIds(Query query) {
        return ((List<Object>) query.getResultList()).stream()
                .map(v -> ((Number) v).longValue())
                .toList();
    }

    /**
     * 세 축(평점·리뷰 수·북마크 수)이 <b>서로 다른 순서를 내는</b> 픽스처. 축마다 동점을 하나씩 심어
     * 타이브레이크와 커서 등호 분기가 실제로 도는 자리를 만든다 — 축이 전부 같은 순서면 힌트가
     * 잘못 걸려도 결과가 같아 등가 단언이 공허해진다.
     *
     * <p>평점 동점은 A·B(4.50, 리뷰 수로 갈림) · 리뷰 수 동점은 A·C(3) · 북마크 동점은 A·B(4)다.
     */
    private void givenMixedAxisFixture() {
        insertRatedStats(placeA, townId, 4.50, 3, 4);
        insertRatedStats(placeB, townId, 4.50, 9, 4);
        insertRatedStats(placeC, townId, 3.00, 3, 1);
        insertRatedStats(placeD, townId, 0.00, 0, 9);
    }

    /** 정렬 3종 × 두 페이지가 두 팔에서 같은지 본다 (커서는 1페이지 마지막 행에서 발급부와 같은 식으로) */
    private void assertForceIndexAgrees(String label, List<Long> townIds) {
        Supplier<List<RatingRow>> ratingPage1 = () -> repository.findRatingRows(
                townIds, null, null, null, null, null, null, 2);
        List<RatingRow> ratingOff = withForceSortIndex(false, ratingPage1);
        assertThat(withForceSortIndex(true, ratingPage1))
                .as("%s 평점순 1페이지", label).isEqualTo(ratingOff);

        RatingRow boundary = ratingOff.get(ratingOff.size() - 1);
        Supplier<List<RatingRow>> ratingPage2 = () -> repository.findRatingRows(
                townIds, null, null, null, boundary.avgRating().doubleValue(),
                boundary.reviewCount(), boundary.placeId(), NO_LIMIT);
        List<RatingRow> ratingPage2Off = withForceSortIndex(false, ratingPage2);
        assertThat(ratingPage2Off).as("%s 평점순 2페이지 (비어 있으면 대조가 공허하다)", label)
                .isNotEmpty();
        assertThat(withForceSortIndex(true, ratingPage2))
                .as("%s 평점순 2페이지", label).isEqualTo(ratingPage2Off);

        assertCountSortAgrees(label + " 리뷰순", CountRow::reviewCount,
                (cursorCount, cursorPlaceId, limit) -> repository.findReviewCountRows(
                        townIds, null, null, null, cursorCount, cursorPlaceId, limit));
        assertCountSortAgrees(label + " 북마크순", CountRow::bookmarkCount,
                (cursorCount, cursorPlaceId, limit) -> repository.findBookmarkCountRows(
                        townIds, null, null, null, cursorCount, cursorPlaceId, limit));
    }

    /** 카운트 축 두 정렬은 커서 키만 다르고 절차가 같다 — 정렬 컬럼을 뽑는 함수로 그 하나를 받는다 */
    @FunctionalInterface
    private interface CountPage {
        List<CountRow> find(Long cursorCount, Long cursorPlaceId, int limit);
    }

    private void assertCountSortAgrees(
            String label, ToLongFunction<CountRow> sortKey, CountPage page) {
        Supplier<List<CountRow>> page1 = () -> page.find(null, null, 2);
        List<CountRow> off = withForceSortIndex(false, page1);
        assertThat(withForceSortIndex(true, page1)).as("%s 1페이지", label).isEqualTo(off);

        CountRow boundary = off.get(off.size() - 1);
        Supplier<List<CountRow>> page2 = () -> page.find(
                sortKey.applyAsLong(boundary), boundary.placeId(), NO_LIMIT);
        List<CountRow> page2Off = withForceSortIndex(false, page2);
        assertThat(page2Off).as("%s 2페이지 (비어 있으면 대조가 공허하다)", label).isNotEmpty();
        assertThat(withForceSortIndex(true, page2)).as("%s 2페이지", label).isEqualTo(page2Off);
    }

    private <T> T withForceSortIndex(boolean forced, Supplier<T> action) {
        placeListProperties.setForceSortIndex(forced);
        try {
            return action.get();
        } finally {
            placeListProperties.setForceSortIndex(false);
        }
    }

    /** 같은 쿼리를 두 팔에서 돌려 <b>문장 원문</b>을 대조한다 — 대조 방식의 근거는 호출부 javadoc에 있다 */
    private void assertHintOnlyInFrom(String indexName, Runnable query) {
        placeListProperties.setForceSortIndex(false);
        String off = captureListSql(query);
        placeListProperties.setForceSortIndex(true);
        String on = captureListSql(query);

        assertThat(off).as("%s: 꺼진 팔의 문장", indexName).doesNotContain("FORCE INDEX");
        assertThat(on).as("%s: 켠 팔의 문장", indexName)
                .isEqualTo(off.replace("FROM place_stats ps",
                        "FROM place_stats ps FORCE INDEX (" + indexName + ")"));
    }

    /**
     * 목록 문장 하나를 잡아 원문을 돌려준다. 픽스처가 만든 문장이 섞이지 않게 실행 직전에 비우고,
     * <b>정확히 하나</b>임을 확인한다 — 여러 개가 잡히면 무엇을 대조했는지 말할 수 없다.
     */
    private String captureListSql(Runnable query) {
        SqlStatementProbe.clear();
        query.run();
        List<String> listSqls = SqlStatementProbe.sqls().stream()
                .filter(sql -> sql.contains("FROM place_stats ps"))
                .toList();
        assertThat(listSqls).as("잡힌 목록 문장").hasSize(1);
        return listSqls.get(0);
    }

    /** 문장 안에서 조각이 몇 번 나오는가 — 브랜치 수와 LIMIT 개수를 세는 데 쓴다 */
    private int countOf(String sql, String fragment) {
        return sql.split(java.util.regex.Pattern.quote(fragment), -1).length - 1;
    }

    private List<PopularRow> findPopular(Double cursorScore, Long cursorPlaceId, int limit) {
        return repository.findPopularRows(
                List.of(townId), null, null, null, cursorScore, cursorPlaceId, limit);
    }

    private List<LatestRow> findLatest(Long cursorSecond, Long cursorPlaceId, int limit) {
        return repository.findLatestRows(
                List.of(townId), null, null, null, cursorSecond, cursorPlaceId, limit);
    }

    private List<RatingRow> findRating(
            Double cursorRating, Long cursorReviewCount, Long cursorPlaceId, int limit) {
        return repository.findRatingRows(List.of(townId), null, null, null,
                cursorRating, cursorReviewCount, cursorPlaceId, limit);
    }

    private List<Long> placeIdsOf(List<PopularRow> rows) {
        return rows.stream().map(PopularRow::placeId).toList();
    }

    private List<Long> ratingIdsOf(List<RatingRow> rows) {
        return rows.stream().map(RatingRow::placeId).toList();
    }

    private List<Long> countIdsOf(List<CountRow> rows) {
        return rows.stream().map(CountRow::placeId).toList();
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
     *
     * <p><b>{@code created_at}·{@code tag_bitmask}는 값을 받지 않고 원본에서 읽는다 (V34).</b>
     * 두 컬럼은 places·place_tag의 사본이라 픽스처가 다른 값을 지어내면 "조회가 틀렸다"와
     * "픽스처가 틀렸다"를 구분할 수 없게 된다. 배치가 하는 계산과 같은 식을 여기서도 쓴다.
     *
     * <p><b>⚠️ 태그 연결({@code linkTag})을 먼저 하고 이것을 부를 것.</b> 마스크는 이 문장이 도는
     * 시점의 place_tag를 굳혀 담는다 — 나중에 붙인 태그는 마스크에 없다. 실제 쓰기 경로도 같은
     * 성질이라({@code AdminPlaceService}가 태그를 flush한 뒤 마스크를 짓는다) 이 제약 자체가 계약이다.
     */
    private void insertStats(long placeId, long townId, double score, long bookmarkCount) {
        insertStatsRow(placeId, townId, score, bookmarkCount, 0, 0.0, SCORED_AT);
    }

    /**
     * 아직 채점되지 않은 행 — {@code popular_score}는 0, {@code score_calculated_at}은 NULL이다.
     * 어드민 생성·재활성이 만드는 행과 카운트 배치가 만든 신규 행이 이 형태다.
     */
    private void insertUnscoredStats(long placeId, long townId, long bookmarkCount) {
        insertStatsRow(placeId, townId, 0.0, bookmarkCount, 0, 0.0, null);
    }

    /**
     * 평점 축 픽스처. {@code avgRating}이 0이면 "리뷰가 없어 평점이 0인 장소"다 — 컬럼이 NOT NULL
     * DEFAULT 0이고(V37) 척도가 1~5라 0은 그 뜻으로만 쓰인다. 평점순이 그 행을 <b>맨 뒤에 싣는지</b>가
     * 이 정렬의 핵심 계약이라 0을 픽스처로 직접 세운다.
     * 미채점 행으로 두는 것은 인기순 술어와 얽히지 않게 하기 위해서다.
     */
    private void insertRatedStats(
            long placeId, long townId, double avgRating, long reviewCount, long bookmarkCount) {
        insertStatsRow(placeId, townId, 0.0, bookmarkCount, reviewCount, avgRating, null);
    }

    private void insertStatsRow(
            long placeId, long townId, double score, long bookmarkCount,
            long reviewCount, double avgRating, LocalDateTime scoreCalculatedAt) {
        em.createNativeQuery("""
                INSERT INTO place_stats (place_id, town_id, created_at, tag_bitmask,
                                         popular_score, bookmark_count,
                                         review_count, avg_rating,
                                         score_calculated_at)
                SELECT p.id,
                       :townId,
                       p.created_at,
                       COALESCE((SELECT BIT_OR(1 << pt.tag_id)
                                 FROM place_tag pt WHERE pt.place_id = p.id), 0),
                       :score, :cnt, :reviewCount, :avgRating, :scoreAt
                FROM places p
                WHERE p.id = :placeId
                """)
                .setParameter("placeId", placeId)
                .setParameter("townId", townId)
                .setParameter("score", score)
                .setParameter("cnt", bookmarkCount)
                .setParameter("reviewCount", reviewCount)
                .setParameter("avgRating", avgRating)
                .setParameter("scoreAt", scoreCalculatedAt)
                .executeUpdate();
    }

    /** 거리순 후보 쿼리가 읽는 유일한 places 컬럼 — 픽스처의 장소는 기본이 NULL이다 */
    private void setCoordinates(long placeId, Double latitude, Double longitude) {
        em.createNativeQuery(
                "UPDATE places SET latitude = :lat, longitude = :lng WHERE id = :id")
                .setParameter("lat", latitude)
                .setParameter("lng", longitude)
                .setParameter("id", placeId)
                .executeUpdate();
    }

    /**
     * 네 기준 장소 전부에 미채점 행을 심는다. 최신순의 기준 테이블이 place_stats라
     * <b>행이 없는 장소는 최신순에도 나오지 않는다</b> — 정렬·커서만 보려는 테스트가 매번 네 줄을
     * 쓰지 않게 묶어 둔다.
     */
    private void insertUnscoredStatsForBasePlaces() {
        insertUnscoredStats(placeA, townId, 0);
        insertUnscoredStats(placeB, townId, 0);
        insertUnscoredStats(placeC, townId, 0);
        insertUnscoredStats(placeD, townId, 0);
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

    /**
     * <b>태그 id를 auto-increment에 맡기지 않고 {@code MAX(id) + 1}로 직접 정한다.</b>
     *
     * <p>V34부터 <b>태그 id가 곧 {@code tag_bitmask}의 비트 자리</b>라 62를 넘으면 안 되는데
     * ({@code TagBitmask}), auto-increment 카운터는 롤백해도 되돌아가지 않는다. 이 파일만 스무 개
     * 넘는 태그를 만들고 같은 싱글턴 컨테이너를 다른 IT가 나눠 쓰므로, 맡겨 두면 스위트가 커질수록
     * 상한을 넘어 <b>테스트가 아니라 가드가 터진다</b>. {@code MAX(id) + 1}은 롤백을 따라 되돌아가
     * 실행 순서·개수와 무관하게 시드 다음 자리(35~)에 머문다.
     *
     * <p>운영에서 그 상한을 지키는 것은 {@code AdminTagService#createTag}의 가드다.
     */
    private long nextTagId() {
        return ((Number) em.createNativeQuery("SELECT COALESCE(MAX(id), 0) + 1 FROM tags")
                .getSingleResult()).longValue();
    }

    private long createMainTag(String name) {
        long tagId = nextTagId();
        em.createNativeQuery("""
                INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
                VALUES (:id, :name, 'MAIN', NULL, true, 'PLACE')
                """)
                .setParameter("id", tagId)
                .setParameter("name", name)
                .executeUpdate();
        return tagId;
    }

    /**
     * 서브 태그(OPTION1/OPTION2)를 메인 태그 아래에 만든다. parent_id를 실제 메인 태그로 채우는 것은
     * FK({@code fk_tags_parent})와 도메인 규칙(서브는 메인에 종속) 때문이며, <b>정렬 쿼리의 EXISTS는
     * type도 parent_id도 보지 않는다</b> — 태그 타입 정합은 상위(요청 검증)의 책임이라는 뜻이다.
     * 그래서 픽스처는 타입을 정확히 심어 "정상 입력에서 두 경로가 같은 답을 내는가"만 묻는다.
     */
    private long createSubTag(String name, String type, long parentId) {
        long tagId = nextTagId();
        em.createNativeQuery("""
                INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
                VALUES (:id, :name, :type, :parentId, true, 'PLACE')
                """)
                .setParameter("id", tagId)
                .setParameter("name", name)
                .setParameter("type", type)
                .setParameter("parentId", parentId)
                .executeUpdate();
        return tagId;
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
