package org.sopt.solply_server.domain.place.util;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.cache.PlaceTagCountCache;
import org.springframework.stereotype.Component;

/**
 * 목록 조회에 <b>지역 주도 조인 순서를 강제할지</b> 판단한다. 판단 결과는
 * {@code PlaceListDbQueryRepository}가 옵티마이저 힌트로 옮긴다.
 *
 * <p>세 조건을 <b>모두</b> 만족할 때만 강제한다 — 복수 동네(시 단위), 태그 필터 존재,
 * 유효 태그 규모가 임계 이상. 하나라도 어긋나면 옵티마이저의 선택을 그대로 둔다. 흔한 태그에서는
 * 강제가 약 2배 이득이지만 희귀 태그에서는 최대 43배 손해라, 이 판단은 <b>보수적인 쪽으로</b>
 * 치우쳐 있어야 한다.
 *
 * <p><b>유효 태그 규모 = AND 그룹별 합의 최솟값이다.</b> 세 그룹(메인 / 옵션1 / 옵션2)은 AND로
 * 엮이므로 교집합의 상한이 가장 작은 그룹이고, 그 상한이 임계를 못 넘으면 태그 주도가 유리할
 * 여지가 남는다. 그룹 안은 OR라 합으로 본다.
 */
@Component
@RequiredArgsConstructor
public class PlaceListJoinOrderPolicy {

    /**
     * 유효 태그 규모의 하한(장소 수). 캠페인 실측에서 인기순 시 단위 강제가 700에서 손해
     * (0.68 → 0.90ms), 1,146에서 이득(1.32 → 0.91ms)이라 그 사이의 보수적 경계로 잡았다
     * ({@code 2026-08-11_join-order-threshold}).
     *
     * <p><b>⚠️ 시 단위 지역 후보 1,800 기준의 값이다.</b> 전환점의 본질은 태그 크기와 지역 크기의
     * 비율이라, 장소 수가 자릿수로 변하면 이 상수는 전제를 잃는다 — 그때는 재측정 대상이지
     * 조정 대상이 아니다.
     */
    static final long MIN_EFFECTIVE_TAG_PLACES = 1_000L;

    private final PlaceTagCountCache placeTagCountCache;

    /**
     * 캐시 조회는 앞의 두 조건을 통과한 뒤에만 한다 — 태그 없는 요청이 카운트 로드를 유발하지
     * 않게 하는 순서다.
     */
    public boolean shouldForceRegionFirst(
            List<Long> townIds, Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds) {
        if (townIds == null || townIds.size() < 2) {
            return false;
        }
        if (mainTagId == null) {
            return false;
        }
        return effectiveTagPlaces(mainTagId, subTagAIds, subTagBIds) >= MIN_EFFECTIVE_TAG_PLACES;
    }

    private long effectiveTagPlaces(Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds) {
        Map<Long, Long> counts = placeTagCountCache.counts();
        long effective = sumOf(counts, List.of(mainTagId));
        if (isNotEmpty(subTagAIds)) {
            effective = Math.min(effective, sumOf(counts, subTagAIds));
        }
        if (isNotEmpty(subTagBIds)) {
            effective = Math.min(effective, sumOf(counts, subTagBIds));
        }
        return effective;
    }

    /** 캐시에 없는 태그 id는 0으로 본다 — 모르는 태그를 흔하다고 가정하지 않는다. */
    private long sumOf(Map<Long, Long> counts, List<Long> tagIds) {
        long sum = 0L;
        for (Long tagId : tagIds) {
            sum += counts.getOrDefault(tagId, 0L);
        }
        return sum;
    }

    private boolean isNotEmpty(List<Long> tagIds) {
        return tagIds != null && !tagIds.isEmpty();
    }
}
