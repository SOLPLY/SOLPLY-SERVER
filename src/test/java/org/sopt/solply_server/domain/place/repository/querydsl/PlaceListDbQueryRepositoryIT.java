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
     * 활성 가드. V26 이후 place_stats에는 active 복제본이 없으므로, 내려간 장소를 거르는 책임은
     * <b>전적으로</b> {@code JOIN places p ... AND p.active = 1}에 있다 — 유일한 가드다.
     * 조인 가드를 지우면 점수가 가장 높은 placeC가 결과 맨 앞에 되살아난다.
     */
    @Test
    void places가_비활성이면_ps_행이_있어도_제외된다() {
        insertStats(placeA, townId, 4.0, 0);
        insertStats(placeB, townId, 2.0, 0);
        insertStats(placeC, townId, 6.0, 0);   // 통계 행은 그대로 남아 있다
        deactivatePlace(placeC);

        List<PopularRow> rows = findPopular(null, null, NO_LIMIT);

        assertThat(placeIdsOf(rows)).containsExactly(placeA, placeB);
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

    // === POPULAR: 직전 세대 ===

    /**
     * <b>세대 분기의 본체.</b> 커서가 직전 세대의 것이면 정렬 축은 {@code prev_popular_score}여야 한다.
     *
     * <p>두 세대의 순서를 <b>완전히 뒤집어</b> 세운다 — 현 세대는 B·C·A, 직전 세대는 A·B·C다.
     * 이래야 "정렬 컬럼만 바꿨는지"를 순서 하나로 판정할 수 있다. 두 세대가 조금이라도 비슷하면
     * 분기가 통째로 빠져도(늘 현 세대로 정렬해도) 부분적으로 맞는 답이 나와 회귀가 숨는다.
     *
     * <p>같은 픽스처에 현 세대 조회를 함께 걸어, 분기가 <em>양방향</em>으로 동작하는지 본다.
     * prev 쪽만 확인하면 "항상 prev로 정렬"이라는 반대 방향 회귀가 살아남는다.
     */
    @Test
    void 직전_세대_정렬은_prev_점수_순서를_따른다() {
        insertStats(placeA, townId, 1.0, 0, 6.0);
        insertStats(placeB, townId, 6.0, 0, 4.0);
        insertStats(placeC, townId, 4.0, 0, 1.0);

        assertThat(placeIdsOf(findPopular(null, null, NO_LIMIT)))
                .containsExactly(placeB, placeC, placeA);
        assertThat(placeIdsOf(findPrevPopular(null, null, NO_LIMIT)))
                .containsExactly(placeA, placeB, placeC);
    }

    /**
     * <b>{@code prev IS NULL}은 "그 세대에 이 장소가 없었다"</b>는 뜻이고, 직전 세대의 목록에
     * 없던 장소를 그 세대의 결과에 끼워 넣는 것은 오답이다. 배치 이후 새로 생긴 장소가 그 경우다.
     *
     * <p>제외 대상 placeD에 <b>현 세대 최고점</b>을 주는 것이 핵심이다 — 술어가 빠지면 D가
     * 결과에 나타나는데, MySQL은 {@code ORDER BY prev DESC}에서 NULL을 맨 뒤로 보내므로
     * 순서만 보면 자연스러워 보인다. 그래서 순서가 아니라 <b>포함 여부</b>로 단언한다.
     */
    @Test
    void 직전_세대에_없던_장소는_prev_정렬에서_빠진다() {
        insertStats(placeA, townId, 1.0, 0, 6.0);
        insertStats(placeB, townId, 2.0, 0, 4.0);
        insertStats(placeD, townId, 99.0, 0, null);   // 배치 이후 생긴 신규 장소

        List<PopularRow> rows = findPrevPopular(null, null, NO_LIMIT);

        assertThat(placeIdsOf(rows)).containsExactly(placeA, placeB);
        // 현 세대에서는 같은 장소가 정상적으로 맨 앞에 나온다 — 제외가 세대 분기의 성질임을 못 박는다
        assertThat(placeIdsOf(findPopular(null, null, NO_LIMIT))).contains(placeD);
    }

    /**
     * <b>정렬만 바꾸고 커서 술어를 안 바꾸면 페이징이 어긋난다.</b> 커서의 sortKey는 직전 세대의
     * 점수인데 술어가 현 세대 컬럼을 비교하면, 두 세대의 값이 다른 만큼 경계가 엉뚱한 곳에 찍힌다.
     *
     * <p>그 어긋남이 <b>반드시 드러나게</b> 픽스처를 세운다: 커서는 placeA(prev 6.0) 다음이므로
     * 정답은 [B, C]다. 술어가 현 세대 컬럼({@code popular_score})을 본다면 "현 점수 &lt; 6.0"이
     * 되어 A(1.0)와 C(4.0)가 통과하고 B(6.0)가 탈락해 [C, A]가 나온다 — 항목도 순서도 다르다.
     */
    @Test
    void 직전_세대_커서는_prev_점수로_경계를_잡는다() {
        insertStats(placeA, townId, 1.0, 0, 6.0);
        insertStats(placeB, townId, 6.0, 0, 4.0);
        insertStats(placeC, townId, 4.0, 0, 1.0);

        List<PopularRow> rows = findPrevPopular(6.0, placeA, NO_LIMIT);

        assertThat(placeIdsOf(rows)).containsExactly(placeB, placeC);
    }

    /**
     * <b>표시 카운트는 세대와 무관하게 현재 값이다.</b> 세대가 고정하는 것은 <em>순위</em>뿐이고,
     * 화면의 북마크 수는 증분이 방금 올린 값이 즉시 보여야 한다(그게 증분을 만든 이유다).
     * {@code bookmark_count}는 세대별 복사본이 없으므로 prev 정렬도 같은 컬럼을 싣는다 —
     * 그 사실을 값으로 못 박아, 나중에 카운트까지 세대별로 나누려는 시도가 여기서 걸리게 한다.
     */
    @Test
    void 직전_세대_정렬도_표시_카운트는_현재_값을_싣는다() {
        insertStats(placeA, townId, 1.0, 7, 6.0);

        List<PopularRow> rows = findPrevPopular(null, null, NO_LIMIT);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).bookmarkCount()).isEqualTo(7);
        // 정렬 키로 실려 나오는 값은 prev 쪽이다 — 서비스가 다음 커서에 담을 값이라 축이 갈리면 안 된다
        assertThat(rows.get(0).popularScore()).isEqualTo(6.0);
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
                List.of(townId), mainTagId, List.of(subA), List.of(subB), null, null, NO_LIMIT);

        assertThat(latestIdsOf(rows)).containsExactly(placeB, placeA);
    }

    // === helpers ===

    private List<PopularRow> findPopular(Double cursorScore, Long cursorPlaceId, int limit) {
        return repository.findPopularRows(
                List.of(townId), null, null, null, false, cursorScore, cursorPlaceId, limit);
    }

    /** 위와 같은 조회를 <b>직전 세대</b> 축으로 건다 — 두 헬퍼의 차이는 세대 플래그 하나뿐이다 */
    private List<PopularRow> findPrevPopular(Double cursorScore, Long cursorPlaceId, int limit) {
        return repository.findPopularRows(
                List.of(townId), null, null, null, true, cursorScore, cursorPlaceId, limit);
    }

    private List<LatestRow> findLatest(Long cursorSecond, Long cursorPlaceId, int limit) {
        return repository.findLatestRows(
                List.of(townId), null, null, null, cursorSecond, cursorPlaceId, limit);
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
     * place_stats에 행을 직접 심는다 — 정렬 쿼리는 배치 결과를 읽을 뿐이므로 배치를 돌릴 필요가 없다.
     * calculated_at은 정렬 쿼리가 읽지 않는 컬럼이라 아무 시각이나 무방하다(NOW(6)).
     */
    private void insertStats(long placeId, long townId, double score, long bookmarkCount) {
        insertStats(placeId, townId, score, bookmarkCount, null);
    }

    /**
     * 직전 세대 점수까지 심는 판. {@code prevScore}가 null이면 컬럼도 NULL —
     * "직전 세대에 이 장소가 없었다"(배치 이후 생긴 신규 장소)를 뜻한다.
     */
    private void insertStats(
            long placeId, long townId, double score, long bookmarkCount, Double prevScore) {
        em.createNativeQuery("""
                INSERT INTO place_stats (place_id, town_id, popular_score, prev_popular_score,
                                         bookmark_count, review_count, avg_rating, calculated_at)
                VALUES (:placeId, :townId, :score, :prevScore, :cnt, 0, NULL, NOW(6))
                """)
                .setParameter("placeId", placeId)
                .setParameter("townId", townId)
                .setParameter("score", score)
                .setParameter("prevScore", prevScore)
                .setParameter("cnt", bookmarkCount)
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
        em.createNativeQuery("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (:name, 'db모드IT', :townId, true, :createdAt)
                """)
                .setParameter("name", name)
                .setParameter("townId", townId)
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
