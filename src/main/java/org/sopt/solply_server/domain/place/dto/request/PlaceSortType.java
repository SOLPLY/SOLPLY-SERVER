package org.sopt.solply_server.domain.place.dto.request;

/**
 * 장소 리스트 정렬 기준. 미지정 시 LATEST (기존 동작 유지)
 *
 * <p><b>{@code keyArity}는 커서가 실어야 하는 정렬 키의 개수다.</b> 커서는 "정렬 키 튜플 + placeId"
 * 형태이고({@code PlaceListCursor} v6), 튜플의 길이가 정렬마다 다르다 — 평점순은 동점 구간을
 * 리뷰 수로 한 번 더 가르므로 두 칸, 거리순은 기준 좌표까지 박제하므로 세 칸이다.
 * <b>여기 숫자와 정렬 쿼리의 seek 조건은 한 쌍이다</b> — 한쪽만 바꾸면 커서가 조용히 어긋난다.
 */
public enum PlaceSortType {

    /** 생성일 DESC, id DESC */
    LATEST(1),

    /** 인기 점수 DESC, id ASC */
    POPULAR(1),

    /** 평점 DESC, 리뷰 수 DESC, id ASC — 키가 둘이라 커서도 둘을 싣는다 */
    RATING(2),

    /** 리뷰 수 DESC, id ASC */
    REVIEW_COUNT(1),

    /** 북마크 수 DESC, id ASC */
    BOOKMARK_COUNT(1),

    /** 기준 좌표로부터의 거리 ASC, id ASC — 커서가 (기준 위도, 기준 경도, 거리)를 싣는다 */
    DISTANCE(3);

    private final int keyArity;

    PlaceSortType(int keyArity) {
        this.keyArity = keyArity;
    }

    /** 이 정렬의 커서가 싣는 정렬 키 개수 (placeId는 별도 필드라 여기 세지 않는다) */
    public int keyArity() {
        return keyArity;
    }
}
