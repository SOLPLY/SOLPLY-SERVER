package org.sopt.solply_server.domain.place.sort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 거리순 정렬 — 후보의 거리를 재고, 커서 뒤의 항목만 <b>가까운 순</b>으로 잘라 준다.
 *
 * <p><b>정렬이 DB가 아니라 여기 있는 이유.</b> 기준점이 요청마다 다르므로 인덱스가 미리 만들어 둘
 * 수 있는 순서가 없다. 그래서 필터를 통과한 후보를 전량 받아(시 단위 ~1,800건) 앱에서 정렬한다.
 *
 * <p><b>전순서는 (거리 ASC, placeId ASC)다.</b> 같은 건물의 여러 가게처럼 좌표가 완전히 같은 장소가
 * 흔하고, 거리만으로 정렬하면 그들 사이 순서가 정해지지 않아 페이지 경계가 흔들린다 — placeId
 * 타이브레이크가 그것을 막는다. 커서 비교가 거리 <em>원값</em> 대조로 성립하는 것은 같은 좌표가
 * 언제나 같은 비트를 내기 때문인데, {@code Math.sin/cos}는 1 ulp 오차가 허용돼 인스턴스(플랫폼)마다
 * 마지막 비트가 다를 수 있다 — 커서가 인스턴스를 건너도 성립해야 하므로 {@link StrictMath}를 쓴다.
 */
public final class DistanceSort {

    /** 지구 평균 반지름(m). 하버사인은 구 모델이라 이 한 값이 오차 특성을 정한다 */
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private static final Comparator<Ranked> NEAREST_FIRST =
            Comparator.comparingDouble(Ranked::distanceMeters).thenComparingLong(Ranked::placeId);

    private DistanceSort() {
    }

    public record Candidate(long placeId, double latitude, double longitude) {}

    public record Ranked(long placeId, double distanceMeters) {}

    /** 정렬: distance ASC, tie는 placeId ASC. cursor가 null이 아니면 (cursorDistance, cursorPlaceId) 뒤의 항목만. limit개 반환(호출자가 hasNext용으로 limit+1을 넘긴다). */
    public static List<Ranked> topK(Iterable<Candidate> candidates,
            double refLat, double refLng,
            Double cursorDistance, Long cursorPlaceId, int limit) {

        // 반쪽 커서는 "커서 없음"이 아니라 호출부의 버그다. 조용히 넘기면 첫 페이지가 반복된다.
        // limit 조기 반환보다 앞에 두어야 limit=0인 호출에서도 그 버그가 드러난다.
        if ((cursorDistance == null) != (cursorPlaceId == null)) {
            throw new IllegalArgumentException(
                    "커서는 거리와 placeId가 함께 있거나 함께 없어야 한다: distance=" + cursorDistance
                            + ", placeId=" + cursorPlaceId);
        }
        if (limit <= 0) {
            return List.of();
        }

        // 크기 limit의 max-heap(머리 = 가장 먼 항목)으로 O(n log limit). 전량 정렬 O(n log n)이 아니라
        // 이 방식인 것이 설계 결정이다 — 요청마다 도는 경로라 페이지 크기만큼만 유지 비용을 낸다.
        PriorityQueue<Ranked> heap = new PriorityQueue<>(NEAREST_FIRST.reversed());
        for (Candidate candidate : candidates) {
            double distance = haversineMeters(
                    refLat, refLng, candidate.latitude(), candidate.longitude());
            if (cursorDistance != null
                    && !isAfterCursor(distance, candidate.placeId(),
                    cursorDistance, cursorPlaceId)) {
                continue;
            }
            if (heap.size() < limit) {
                heap.offer(new Ranked(candidate.placeId(), distance));
                continue;
            }
            // 탈락 판정은 할당 전에 primitive로 한다 — 힙이 찬 뒤의 후보 대부분이 여기서 걸러지고,
            // 걸러진 후보는 Ranked를 만들지 않는다. 이 비교는 NEAREST_FIRST와 같은 전순서여야 한다.
            Ranked worst = heap.peek();
            int byDistance = Double.compare(distance, worst.distanceMeters());
            if (byDistance < 0 || (byDistance == 0 && candidate.placeId() < worst.placeId())) {
                heap.poll();
                heap.offer(new Ranked(candidate.placeId(), distance));
            }
        }

        List<Ranked> page = new ArrayList<>(heap);
        page.sort(NEAREST_FIRST);
        return page;
    }

    /**
     * 커서 경계. 거리가 같은 구간에서 <b>placeId 초과</b>만 남기는 것이 핵심이다 — 거리만 비교하면
     * 경계에 걸린 동률 항목이 통째로 다시 나오거나(이상) 통째로 건너뛰어진다(미만).
     */
    private static boolean isAfterCursor(
            double distance, long placeId, double cursorDistance, long cursorPlaceId) {
        return distance > cursorDistance
                || (distance == cursorDistance && placeId > cursorPlaceId);
    }

    /** 두 지점의 대권 거리(m). 위경도는 도(degree) 단위로 받는다. StrictMath인 이유는 클래스 javadoc */
    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double lat1Rad = StrictMath.toRadians(lat1);
        double lat2Rad = StrictMath.toRadians(lat2);
        double halfLatDelta = StrictMath.toRadians(lat2 - lat1) / 2;
        double halfLngDelta = StrictMath.toRadians(lng2 - lng1) / 2;

        double a = StrictMath.sin(halfLatDelta) * StrictMath.sin(halfLatDelta)
                + StrictMath.cos(lat1Rad) * StrictMath.cos(lat2Rad)
                * StrictMath.sin(halfLngDelta) * StrictMath.sin(halfLngDelta);
        return 2 * EARTH_RADIUS_METERS * StrictMath.asin(StrictMath.min(1.0, StrictMath.sqrt(a)));
    }
}
