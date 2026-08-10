package org.sopt.solply_server.domain.place.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.place.cache.PlaceTagCountCache;

/**
 * 조인 순서 힌트를 <b>언제 붙이는가</b>의 규칙만 못 박는다. 붙인 힌트가 어떤 SQL이 되는지는
 * {@code PlaceListDbQueryRepository}의 몫이고, 그 SQL이 결과를 바꾸지 않는다는 것은 목록 IT가 문다.
 *
 * <p><b>여기서 지키는 것은 "강제는 예외다"라는 성질이다.</b> 흔한 태그에서 얻는 이득(약 2배)보다
 * 희귀 태그에서 잃는 것(최대 43배)이 훨씬 크므로, 확신이 없는 입력은 전부 미부착으로 떨어져야 한다 —
 * 아래 제외 케이스들이 그 확신의 조건을 하나씩 센다.
 */
@ExtendWith(MockitoExtension.class)
class PlaceListJoinOrderPolicyTest {

    private static final long MAIN_TAG = 1L;
    private static final long SUB_A1 = 11L;
    private static final long SUB_A2 = 12L;
    private static final long SUB_B1 = 21L;

    /** 시 단위 스코프 — 동네가 둘 이상이라야 filesort + 태그 주도 전환이 성립한다 */
    private static final List<Long> CITY = List.of(101L, 102L, 103L);
    private static final List<Long> SINGLE_TOWN = List.of(101L);

    @Mock private PlaceTagCountCache placeTagCountCache;

    @InjectMocks private PlaceListJoinOrderPolicy policy;

    @Test
    void 시_단위_흔한_메인태그_단독이면_붙인다() {
        givenCounts(Map.of(MAIN_TAG, 1_500L));

        assertThat(policy.shouldForceRegionFirst(CITY, MAIN_TAG, null, null)).isTrue();
    }

    /** 경계는 임계 <b>이상</b>이다 — 999와 1,000이 갈려야 상수를 옮긴 변경이 드러난다 */
    @Test
    void 임계_직전_999는_붙이지_않는다() {
        givenCounts(Map.of(MAIN_TAG, 999L));

        assertThat(policy.shouldForceRegionFirst(CITY, MAIN_TAG, null, null)).isFalse();
    }

    @Test
    void 임계_정확히_1000이면_붙인다() {
        givenCounts(Map.of(MAIN_TAG, 1_000L));

        assertThat(policy.shouldForceRegionFirst(CITY, MAIN_TAG, null, null)).isTrue();
    }

    /**
     * 그룹 안은 OR라 <b>합</b>이다. 각 태그는 임계에 못 미쳐도 합이 넘으면 그 그룹은 흔한 그룹이다.
     */
    @Test
    void 옵션_그룹은_리스트_합으로_센다() {
        givenCounts(Map.of(MAIN_TAG, 5_000L, SUB_A1, 600L, SUB_A2, 500L));

        assertThat(policy.shouldForceRegionFirst(CITY, MAIN_TAG, List.of(SUB_A1, SUB_A2), null))
                .isTrue();
    }

    /**
     * 그룹 간은 AND라 <b>최솟값</b>이다. 메인이 아무리 흔해도 좁은 옵션 그룹 하나가 교집합의
     * 상한을 눌러 버리므로, 그 그룹을 기준으로 판단해야 한다.
     */
    @Test
    void 그룹이_여럿이면_가장_작은_그룹이_판단_기준이다() {
        givenCounts(Map.of(MAIN_TAG, 5_000L, SUB_A1, 4_000L, SUB_B1, 300L));

        assertThat(policy.shouldForceRegionFirst(CITY, MAIN_TAG, List.of(SUB_A1), List.of(SUB_B1)))
                .isFalse();
    }

    /** 단일 동네는 정렬 인덱스가 이미 순서를 만들어 강제할 것이 없다 */
    @Test
    void 단일_동네는_붙이지_않는다() {
        assertThat(policy.shouldForceRegionFirst(SINGLE_TOWN, MAIN_TAG, null, null)).isFalse();
    }

    /** 태그 필터가 없으면 EXISTS 자체가 없다 — 뒤집을 조인이 없다 */
    @Test
    void 태그_필터가_없으면_붙이지_않는다() {
        assertThat(policy.shouldForceRegionFirst(CITY, null, null, null)).isFalse();
    }

    /**
     * 캐시에 없는 태그는 0이다. "모르니까 흔할 것"으로 기울면 희귀 태그에서 최대 43배 손해를
     * 그대로 밟는다 — 모르는 쪽은 강제하지 않는 것이 안전한 방향이다.
     */
    @Test
    void 캐시에_없는_태그는_0으로_본다() {
        givenCounts(Map.of());

        assertThat(policy.shouldForceRegionFirst(CITY, MAIN_TAG, null, null)).isFalse();
    }

    /** 옵션 그룹의 태그만 캐시에 없어도 그 그룹의 합이 0이라 최솟값이 0으로 눌린다 */
    @Test
    void 옵션_그룹_태그가_캐시에_없으면_그_그룹이_0이_된다() {
        givenCounts(Map.of(MAIN_TAG, 5_000L));

        assertThat(policy.shouldForceRegionFirst(CITY, MAIN_TAG, List.of(SUB_A1), null)).isFalse();
    }

    private void givenCounts(Map<Long, Long> counts) {
        given(placeTagCountCache.counts()).willReturn(counts);
    }
}
