package org.sopt.solply_server.domain.place.sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.sort.DistanceSort.Candidate;
import org.sopt.solply_server.domain.place.sort.DistanceSort.Ranked;

class DistanceSortTest {

    /** 기준점. 적도·본초자오선이라 위도 0.01도가 곧 약 1.112km로 손으로 검산된다. */
    private static final double REF_LAT = 0.0;
    private static final double REF_LNG = 0.0;

    private static Candidate at(long placeId, double lat, double lng) {
        return new Candidate(placeId, lat, lng);
    }

    private static List<Ranked> topK(List<Candidate> candidates, int limit) {
        return DistanceSort.topK(candidates, REF_LAT, REF_LNG, null, null, limit);
    }

    private static List<Long> idsOf(List<Ranked> ranked) {
        return ranked.stream().map(Ranked::placeId).toList();
    }

    /** 빈 페이지가 나올 때까지 커서로 끝까지 훑는다. 페이지 경계에서 새는 것이 있으면 여기서 드러난다. */
    private static List<Ranked> allPages(List<Candidate> candidates, int pageSize) {
        List<Ranked> collected = new ArrayList<>();
        Double cursorDistance = null;
        Long cursorPlaceId = null;

        while (true) {
            List<Ranked> page = DistanceSort.topK(
                    candidates, REF_LAT, REF_LNG, cursorDistance, cursorPlaceId, pageSize);
            if (page.isEmpty()) {
                return collected;
            }
            collected.addAll(page);
            Ranked last = page.get(page.size() - 1);
            cursorDistance = last.distanceMeters();
            cursorPlaceId = last.placeId();
        }
    }

    // === 정렬 순서 ===

    /**
     * 입력 순서를 일부러 뒤섞어 넣는다. 후보를 받은 순서 그대로 흘려보내는 구현도 "정렬된 것처럼"
     * 보일 수 있어서, 입력이 이미 정렬돼 있으면 아무것도 검증하지 못한다.
     */
    @Test
    void 가까운_순서로_정렬된다() {
        List<Candidate> candidates = List.of(
                at(3L, 0.03, 0.0),
                at(1L, 0.01, 0.0),
                at(4L, 0.04, 0.0),
                at(2L, 0.02, 0.0));

        assertThat(idsOf(topK(candidates, 4))).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void 거리가_먼_후보는_limit_밖으로_밀려난다() {
        List<Candidate> candidates = List.of(
                at(3L, 0.03, 0.0),
                at(1L, 0.01, 0.0),
                at(4L, 0.04, 0.0),
                at(2L, 0.02, 0.0));

        assertThat(idsOf(topK(candidates, 2))).containsExactly(1L, 2L);
    }

    /**
     * 위도 0.01도마다 약 1.112km. 거리 값 자체가 커서로 실려 나가므로 순서뿐 아니라 값도 확인한다.
     */
    @Test
    void 거리를_미터_단위로_매긴다() {
        List<Ranked> ranked = topK(List.of(at(1L, 0.01, 0.0), at(2L, 0.02, 0.0)), 2);

        assertThat(ranked.get(0).distanceMeters()).isCloseTo(1111.95, within(1.0));
        assertThat(ranked.get(1).distanceMeters()).isCloseTo(2223.90, within(1.0));
    }

    /**
     * 알려진 두 지점(서울시청 ↔ 강남역)의 직선 거리는 약 8.78km다. 허용 오차 50m는 지구 반지름
     * 모델 차이를 덮는 폭이고, 라디안 변환 누락이나 위경도 뒤바뀜 같은 실수는 이 폭을 한참 벗어난다.
     */
    @Test
    void 하버사인_거리가_실제_거리에_근사한다() {
        double cityHallLat = 37.5663;
        double cityHallLng = 126.9779;

        List<Ranked> ranked = DistanceSort.topK(
                List.of(at(1L, 37.4979, 127.0276)), cityHallLat, cityHallLng, null, null, 1);

        assertThat(ranked.get(0).distanceMeters()).isCloseTo(8778.0, within(50.0));
    }

    // === 동률 타이브레이크 ===

    /**
     * 좌표가 완전히 같은 장소(같은 건물의 여러 가게)는 흔하다. 거리만으로 정렬하면 이들의 순서가
     * 정해지지 않아 커서 경계가 흔들린다.
     */
    @Test
    void 거리가_같으면_placeId_오름차순이다() {
        List<Candidate> candidates = List.of(
                at(30L, 0.01, 0.0),
                at(10L, 0.01, 0.0),
                at(20L, 0.01, 0.0));

        assertThat(idsOf(topK(candidates, 3))).containsExactly(10L, 20L, 30L);
    }

    @Test
    void 거리가_같은_후보들_사이에서도_limit이_placeId_순으로_잘린다() {
        List<Candidate> candidates = List.of(
                at(30L, 0.01, 0.0),
                at(10L, 0.01, 0.0),
                at(20L, 0.01, 0.0));

        assertThat(idsOf(topK(candidates, 2))).containsExactly(10L, 20L);
    }

    /** 커서 비교가 원값 대조로 성립하려면 같은 좌표가 항상 같은 비트를 내야 한다. */
    @Test
    void 같은_좌표는_항상_같은_거리_비트를_낸다() {
        double first = topK(List.of(at(1L, 37.4979, 127.0276)), 1).get(0).distanceMeters();
        double second = topK(List.of(at(1L, 37.4979, 127.0276)), 1).get(0).distanceMeters();

        assertThat(Double.doubleToRawLongBits(first))
                .isEqualTo(Double.doubleToRawLongBits(second));
    }

    // === 커서 ===

    @Test
    void 커서가_없으면_처음부터_준다() {
        List<Candidate> candidates = List.of(at(1L, 0.01, 0.0), at(2L, 0.02, 0.0));

        assertThat(idsOf(topK(candidates, 2))).containsExactly(1L, 2L);
    }

    /**
     * 전 페이지 마지막 항목을 커서로 삼아 끝까지 훑었을 때, 커서 없이 한 번에 받은 전체 목록과
     * <b>정확히 같아야</b> 한다. 중복이 있으면 개수가 늘고, 누락이 있으면 줄어든다.
     */
    @Test
    void 커서로_이어받으면_중복도_누락도_없다() {
        List<Candidate> candidates = List.of(
                at(5L, 0.05, 0.0),
                at(1L, 0.01, 0.0),
                at(3L, 0.03, 0.0),
                at(2L, 0.02, 0.0),
                at(4L, 0.04, 0.0));

        assertThat(allPages(candidates, 2)).isEqualTo(topK(candidates, 5));
        assertThat(idsOf(allPages(candidates, 2))).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    /**
     * <b>동일 거리가 페이지 경계에 걸린 경우가 가장 위험하다.</b> 거리만 비교하면 경계의 동률 항목이
     * 통째로 다시 나오거나(중복) 통째로 건너뛰어진다(누락). placeId 타이브레이크가 이것을 막는다.
     */
    @Test
    void 거리가_모두_같아도_커서로_중복_없이_이어진다() {
        List<Candidate> candidates = List.of(
                at(50L, 0.01, 0.0),
                at(10L, 0.01, 0.0),
                at(40L, 0.01, 0.0),
                at(20L, 0.01, 0.0),
                at(30L, 0.01, 0.0));

        assertThat(idsOf(allPages(candidates, 2))).containsExactly(10L, 20L, 30L, 40L, 50L);
    }

    /** 거리가 같을 때 커서와 placeId가 같은 항목(경계 자신)은 다시 나오면 안 된다. */
    @Test
    void 거리가_같으면_커서_placeId_이하는_제외한다() {
        List<Candidate> candidates = List.of(
                at(10L, 0.01, 0.0),
                at(20L, 0.01, 0.0),
                at(30L, 0.01, 0.0));
        double sameDistance = topK(candidates, 1).get(0).distanceMeters();

        List<Ranked> next = DistanceSort.topK(
                candidates, REF_LAT, REF_LNG, sameDistance, 20L, 10);

        assertThat(idsOf(next)).containsExactly(30L);
    }

    @Test
    void 커서가_마지막_항목이면_빈_리스트다() {
        List<Candidate> candidates = List.of(at(1L, 0.01, 0.0), at(2L, 0.02, 0.0));
        Ranked last = topK(candidates, 2).get(1);

        List<Ranked> next = DistanceSort.topK(
                candidates, REF_LAT, REF_LNG, last.distanceMeters(), last.placeId(), 10);

        assertThat(next).isEmpty();
    }

    /** 반쪽 커서는 "커서 없음"이 아니라 호출부의 버그다. 조용히 넘기면 첫 페이지가 반복된다. */
    @Test
    void 커서가_반쪽만_있으면_예외를_던진다() {
        List<Candidate> candidates = List.of(at(1L, 0.01, 0.0));

        assertThatThrownBy(() -> DistanceSort.topK(
                candidates, REF_LAT, REF_LNG, 100.0, null, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DistanceSort.topK(
                candidates, REF_LAT, REF_LNG, null, 1L, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** limit 조기 반환이 인자 검증을 가려선 안 된다 — 잘못된 호출은 limit과 무관하게 잘못된 호출이다. */
    @Test
    void 반쪽_커서는_limit이_0이어도_예외를_던진다() {
        assertThatThrownBy(() -> DistanceSort.topK(
                List.of(), REF_LAT, REF_LNG, 100.0, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // === limit 경계 ===

    @Test
    void limit이_0이면_빈_리스트다() {
        assertThat(topK(List.of(at(1L, 0.01, 0.0)), 0)).isEmpty();
    }

    @Test
    void limit이_음수이면_빈_리스트다() {
        assertThat(topK(List.of(at(1L, 0.01, 0.0)), -1)).isEmpty();
    }

    @Test
    void limit이_1이면_가장_가까운_하나만_준다() {
        List<Candidate> candidates = List.of(
                at(3L, 0.03, 0.0),
                at(1L, 0.01, 0.0),
                at(2L, 0.02, 0.0));

        assertThat(idsOf(topK(candidates, 1))).containsExactly(1L);
    }

    @Test
    void limit이_후보_수보다_크면_전부_준다() {
        List<Candidate> candidates = List.of(at(2L, 0.02, 0.0), at(1L, 0.01, 0.0));

        assertThat(idsOf(topK(candidates, 100))).containsExactly(1L, 2L);
    }

    @Test
    void 후보가_비면_빈_리스트다() {
        assertThat(topK(List.of(), 10)).isEmpty();
    }

    @Test
    void 커서가_있어도_후보가_비면_빈_리스트다() {
        assertThat(DistanceSort.topK(List.of(), REF_LAT, REF_LNG, 100.0, 1L, 10)).isEmpty();
    }
}
