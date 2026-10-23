package org.sopt.solply_server.domain.place.cache;

import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;

/**
 * 정적 정렬 다섯 축의 <b>비교 규칙 정본</b>. 배열을 만들 때 쓰는 비교와 커서에서 이어 붙일 자리를
 * 찾는 비교가 여기 한 곳에 같이 있다.
 *
 * <p><b>두 비교가 한 enum 상수 안에 있어야 하는 이유.</b> 커서 탐색은 정렬 배열에 대한 이진
 * 탐색이라 {@link #compareToCursor}가 {@link #compare}와 <b>같은 전순서</b>를 말하지 않으면
 * 조용히 엉뚱한 자리를 짚는다 — 페이지가 겹치거나 빠진다. 둘을 다른 파일에 두면 한쪽만 고치는
 * 일이 반드시 생긴다.
 *
 * <p>거리순은 여기 없다. 기준 좌표가 요청마다 달라 미리 정렬해 둘 수 없다.
 */
public enum PlaceOrder {

    /** 인기점수 내림차순, 동점은 id 오름차순. */
    POPULAR(PlaceSortType.POPULAR) {
        @Override
        public int compare(PlaceEntry a, PlaceEntry b) {
            int byScore = Double.compare(b.popularScore(), a.popularScore());
            return byScore != 0 ? byScore : Long.compare(a.placeId(), b.placeId());
        }

        @Override
        public int compareToCursor(PlaceEntry entry, PlaceListCursor cursor) {
            int byScore = Double.compare(cursor.key(0), entry.popularScore());
            return byScore != 0 ? byScore : Long.compare(entry.placeId(), cursor.placeId());
        }
    },

    /** 생성 시각 내림차순, 동점은 id 내림차순. */
    LATEST(PlaceSortType.LATEST) {
        @Override
        public int compare(PlaceEntry a, PlaceEntry b) {
            int byCreatedAt = Long.compare(b.createdAtEpochSecond(), a.createdAtEpochSecond());
            return byCreatedAt != 0 ? byCreatedAt : Long.compare(b.placeId(), a.placeId());
        }

        @Override
        public int compareToCursor(PlaceEntry entry, PlaceListCursor cursor) {
            int byCreatedAt = Long.compare((long) cursor.key(0), entry.createdAtEpochSecond());
            return byCreatedAt != 0
                    ? byCreatedAt
                    : Long.compare(cursor.placeId(), entry.placeId());
        }
    },

    /** 평점 내림차순 → 리뷰 수 내림차순 → id 오름차순. */
    RATING(PlaceSortType.RATING) {
        @Override
        public int compare(PlaceEntry a, PlaceEntry b) {
            int byRating = Integer.compare(b.ratingToInt(), a.ratingToInt());
            if (byRating != 0) {
                return byRating;
            }
            int byReviews = Long.compare(b.reviewCount(), a.reviewCount());
            return byReviews != 0 ? byReviews : Long.compare(a.placeId(), b.placeId());
        }

        @Override
        public int compareToCursor(PlaceEntry entry, PlaceListCursor cursor) {
            int byRating = Integer.compare(cursorRatingToInt(cursor.key(0)), entry.ratingToInt());
            if (byRating != 0) {
                return byRating;
            }
            int byReviews = Long.compare((long) cursor.key(1), entry.reviewCount());
            return byReviews != 0 ? byReviews : Long.compare(entry.placeId(), cursor.placeId());
        }
    },

    /** 리뷰 수 내림차순, 동점은 id 오름차순. */
    REVIEW_COUNT(PlaceSortType.REVIEW_COUNT) {
        @Override
        public int compare(PlaceEntry a, PlaceEntry b) {
            int byCount = Long.compare(b.reviewCount(), a.reviewCount());
            return byCount != 0 ? byCount : Long.compare(a.placeId(), b.placeId());
        }

        @Override
        public int compareToCursor(PlaceEntry entry, PlaceListCursor cursor) {
            int byCount = Long.compare((long) cursor.key(0), entry.reviewCount());
            return byCount != 0 ? byCount : Long.compare(entry.placeId(), cursor.placeId());
        }
    },

    /** 북마크 수 내림차순, 동점은 id 오름차순. */
    BOOKMARK_COUNT(PlaceSortType.BOOKMARK_COUNT) {
        @Override
        public int compare(PlaceEntry a, PlaceEntry b) {
            int byCount = Long.compare(b.bookmarkCount(), a.bookmarkCount());
            return byCount != 0 ? byCount : Long.compare(a.placeId(), b.placeId());
        }

        @Override
        public int compareToCursor(PlaceEntry entry, PlaceListCursor cursor) {
            int byCount = Long.compare((long) cursor.key(0), entry.bookmarkCount());
            return byCount != 0 ? byCount : Long.compare(entry.placeId(), cursor.placeId());
        }
    };

    private final PlaceSortType sortType;

    PlaceOrder(PlaceSortType sortType) {
        this.sortType = sortType;
    }

    public PlaceSortType sortType() {
        return sortType;
    }

    /** 배열을 만들 때의 비교. */
    public abstract int compare(PlaceEntry a, PlaceEntry b);

    /**
     * 커서가 가리키는 자리와의 비교. 양수면 엔트리가 커서보다 <b>뒤</b>다 — 이진 탐색이 찾는
     * 첫 자리가 그 지점이다.
     */
    public abstract int compareToCursor(PlaceEntry entry, PlaceListCursor cursor);

    /** 커서의 평점 키는 소수 둘짜리 실수고 엔트리는 100배 정수다. */
    private static int cursorRatingToInt(double cursorKey) {
        return (int) Math.round(cursorKey * 100);
    }

    public static PlaceOrder of(PlaceSortType sort) {
        return switch (sort) {
            case POPULAR -> POPULAR;
            case LATEST -> LATEST;
            case RATING -> RATING;
            case REVIEW_COUNT -> REVIEW_COUNT;
            case BOOKMARK_COUNT -> BOOKMARK_COUNT;
            case DISTANCE -> throw new IllegalArgumentException(
                    "거리순은 사전 정렬 축이 아니다 - 기준 좌표가 요청마다 다르다");
        };
    }
}
