package org.sopt.solply_server.domain.place.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.entity.TagUsage;

/**
 * 태그 필터 의미론의 계약을 못 박는다 — <b>설정한 태그를 전부 가진 장소만</b>(AND-all),
 * 메인 없으면 서브 무시, 원본 순서 보존, null 안전.
 *
 * <p><b>여기서 가장 중요한 것은 "좁아지는가"다.</b> 3세대를 내려온 구 구현은 그룹 안을 OR로 걸어
 * 서브 태그를 고를수록 결과가 <em>넓어졌다</em>. 예외가 나지 않는 오답이라 응답을 눈으로 세기 전까지
 * 드러나지 않았고, 그래서 2026-08-11 교정 이후로는 "둘 다 가진 장소만 남는다"를 이 파일이 문다.
 *
 * <p><b>하나가 늘었다 — 비활성 태그.</b> 캐시 스냅샷은 로더가 active 태그만 담아 왔기 때문에
 * "비활성 태그로는 매치되지 않는다"를 필터 단위에서 물을 수 없었다(로더 IT의 몫이었다).
 * 엔티티에는 비활성 태그가 그대로 붙어 있으므로 이제 그 책임이 여기로 내려온다.
 *
 * <p><b>단언을 id가 아니라 이름으로 하는 이유.</b> {@code Place.id}는 {@code @GeneratedValue}라
 * 영속화 없이는 채울 수 없고, 그걸 채우겠다고 리플렉션을 끌어들이면 테스트가 필드명에 묶인다.
 * 이 필터는 id를 보지 않으므로 식별만 되면 충분하다.
 */
class PlaceTagMatcherTest {

    private static final long MAIN = 10L;
    private static final long OTHER_MAIN = 11L;
    private static final long SUB_A1 = 20L;
    private static final long SUB_A2 = 21L;
    private static final long SUB_B = 30L;

    private Tag tag(long id, TagType type) {
        return tag(id, type, true);
    }

    private Tag tag(long id, TagType type, boolean active) {
        return Tag.builder()
                .id(id)
                .name("t" + id)
                .type(type)
                .active(active)
                .tagUsage(TagUsage.PLACE)
                .build();
    }

    /**
     * town·createdBy를 null로 두는 것은 이 필터가 그 둘을 읽지 않기 때문이다 —
     * DB 제약(NOT NULL)은 영속화 시점의 계약이고 여기는 순수 함수의 무대다.
     */
    private Place place(String name, Tag main, List<Tag> optA, List<Tag> optB) {
        return Place.create(
                name, "소개", null, null, null, null, null,
                null, List.of(), List.of(), null, null, true,
                main, optA, optB);
    }

    private Place place(String name, Tag main) {
        return place(name, main, List.of(), List.of());
    }

    @Test
    void 메인_태그가_null이면_원본을_그대로_반환한다() {
        List<Place> places = List.of(place("p1", tag(MAIN, TagType.MAIN)));

        assertThat(PlaceTagMatcher.filter(places, null, null, null)).isEqualTo(places);
    }

    @Test
    void 메인_태그는_active_MAIN_태그_일치로_필터링한다() {
        List<Place> places = List.of(
                place("p1", tag(MAIN, TagType.MAIN)),
                place("p2", tag(OTHER_MAIN, TagType.MAIN)));

        assertThat(PlaceTagMatcher.filter(places, MAIN, null, null))
                .extracting(Place::getName).containsExactly("p1");
    }

    /**
     * <b>한 그룹에서 둘을 고르면 둘 다 가진 장소만 남는다.</b> 구 OR 구현이었다면 하나만 가진
     * p1·p2까지 통과해 결과가 셋이 된다 — 이 테스트가 그 회귀를 정면으로 막는다.
     *
     * <p>p3에 <em>요청에 없는</em> OPTION1 태그를 하나 더 붙인 것은 "전부 포함"이 "정확히 일치"가
     * 아님을 함께 못 박기 위해서다. 남는 태그가 있어도 통과해야 한다.
     */
    @Test
    void 같은_그룹의_태그를_전부_가진_장소만_통과한다() {
        List<Place> places = List.of(
                place("p1", tag(MAIN, TagType.MAIN), List.of(tag(SUB_A1, TagType.OPTION1)), List.of()),
                place("p2", tag(MAIN, TagType.MAIN), List.of(tag(SUB_A2, TagType.OPTION1)), List.of()),
                place("p3", tag(MAIN, TagType.MAIN),
                        List.of(tag(SUB_A1, TagType.OPTION1), tag(SUB_A2, TagType.OPTION1),
                                tag(29L, TagType.OPTION1)),
                        List.of()));

        assertThat(PlaceTagMatcher.filter(places, MAIN, List.of(SUB_A1, SUB_A2), null))
                .extracting(Place::getName).containsExactly("p3");
    }

    /**
     * <b>하나만 가진 장소는 제외된다 — 위 테스트를 최소 형상으로 다시 세운 짝이다.</b>
     * 통과자를 두지 않아 "필터가 통째로 빠지면 둘 다 살아남는" 회귀가 <em>빈 결과</em>로 드러난다.
     */
    @Test
    void 요청_태그_중_하나만_가진_장소는_제외된다() {
        List<Place> places = List.of(
                place("p1", tag(MAIN, TagType.MAIN), List.of(tag(SUB_A1, TagType.OPTION1)), List.of()),
                place("p2", tag(MAIN, TagType.MAIN), List.of(tag(SUB_A2, TagType.OPTION1)), List.of()));

        assertThat(PlaceTagMatcher.filter(places, MAIN, List.of(SUB_A1, SUB_A2), null)).isEmpty();
    }

    /** 그룹이 달라도 규칙은 하나다 — 메인·서브A·서브B를 모두 만족해야 통과한다. */
    @Test
    void 메인과_서브_그룹은_모두_만족해야_통과한다() {
        List<Place> places = List.of(
                place("p1", tag(MAIN, TagType.MAIN),
                        List.of(tag(SUB_A1, TagType.OPTION1)), List.of(tag(SUB_B, TagType.OPTION2))),
                place("p2", tag(MAIN, TagType.MAIN),
                        List.of(tag(SUB_A1, TagType.OPTION1)), List.of()),
                place("p3", tag(MAIN, TagType.MAIN),
                        List.of(), List.of(tag(SUB_B, TagType.OPTION2))));

        assertThat(PlaceTagMatcher.filter(places, MAIN, List.of(SUB_A1), List.of(SUB_B)))
                .extracting(Place::getName).containsExactly("p1");
    }

    @Test
    void 서브_태그_리스트가_빈_리스트면_조건_없음으로_취급한다() {
        List<Place> places = List.of(place("p1", tag(MAIN, TagType.MAIN)));

        assertThat(PlaceTagMatcher.filter(places, MAIN, List.of(), List.of()))
                .extracting(Place::getName).containsExactly("p1");
    }

    @Test
    void 원본_순서를_보존한다() {
        List<Place> places = List.of(
                place("p3", tag(MAIN, TagType.MAIN)),
                place("p1", tag(MAIN, TagType.MAIN)),
                place("p2", tag(MAIN, TagType.MAIN)));

        assertThat(PlaceTagMatcher.filter(places, MAIN, null, null))
                .extracting(Place::getName).containsExactly("p3", "p1", "p2");
    }

    @Test
    void 메인_태그가_없으면_서브_태그_조건은_무시한다() {
        List<Place> places = List.of(
                place("p1", tag(MAIN, TagType.MAIN), List.of(tag(SUB_A1, TagType.OPTION1)), List.of()),
                place("p2", tag(OTHER_MAIN, TagType.MAIN), List.of(tag(SUB_A2, TagType.OPTION1)), List.of()));

        assertThat(PlaceTagMatcher.filter(places, null, List.of(SUB_A1), null))
                .extracting(Place::getName).containsExactly("p1", "p2");
    }

    /**
     * <b>후보의 null은 조건에서 빠질 뿐 결과를 비우지 않는다.</b> AND-all에서 null을 "불일치"로
     * 세면 잡음 하나가 목록을 통째로 비운다 — 태그 id가 아니라 파싱 잡음이므로 없는 셈 친다.
     * (OR 시절에는 건너뛰어도 다른 후보가 통과를 만들어 이 선택이 드러나지 않았다.)
     */
    @Test
    void 서브_태그_후보에_null이_섞여도_예외_없이_남은_조건으로_판정한다() {
        List<Place> places = List.of(
                place("p1", tag(MAIN, TagType.MAIN), List.of(tag(SUB_A1, TagType.OPTION1)), List.of()),
                place("p2", tag(MAIN, TagType.MAIN), List.of(tag(SUB_A2, TagType.OPTION1)), List.of()));

        assertThat(PlaceTagMatcher.filter(places, MAIN, Arrays.asList(null, SUB_A1), null))
                .extracting(Place::getName).containsExactly("p1");
    }

    /**
     * <b>비활성 태그로는 매치되지 않는다.</b> 어드민이 태그를 내리면 그 태그로 걸러진 목록은
     * 비어야 한다 — 붙어 있던 관계가 아니라 태그의 현재 상태가 기준이다.
     * 두 축을 함께 세운다: 메인이 비활성인 p1, 서브A가 비활성인 p2. 어느 한쪽 필터에서
     * {@code isActive()} 검사가 빠지면 그쪽 장소가 되살아난다.
     */
    @Test
    void 비활성_태그는_매치되지_않는다() {
        List<Place> places = List.of(
                place("p1", tag(MAIN, TagType.MAIN, false),
                        List.of(tag(SUB_A1, TagType.OPTION1)), List.of()),
                place("p2", tag(MAIN, TagType.MAIN),
                        List.of(tag(SUB_A1, TagType.OPTION1, false)), List.of()),
                place("p3", tag(MAIN, TagType.MAIN),
                        List.of(tag(SUB_A1, TagType.OPTION1)), List.of()));

        assertThat(PlaceTagMatcher.filter(places, MAIN, List.of(SUB_A1), null))
                .extracting(Place::getName).containsExactly("p3");
    }

    /**
     * <b>타입 축을 실제로 본다.</b> 같은 태그 id가 자리만 바꿔 오면 매치돼선 안 된다 —
     * {@code activeTagIds(place, MAIN)}에서 타입 필터가 빠지면 이 케이스가 통과해 버린다.
     * (스냅샷은 버킷이 이미 타입별이라 구조가 막아 주던 일이고, 엔티티에서는 코드가 막아야 한다.)
     */
    @Test
    void 타입이_다른_같은_id_태그는_메인_자리에서_매치되지_않는다() {
        List<Place> places = List.of(
                place("p1", null, List.of(tag(MAIN, TagType.OPTION1)), List.of()));

        assertThat(PlaceTagMatcher.filter(places, MAIN, null, null)).isEmpty();
    }
}
