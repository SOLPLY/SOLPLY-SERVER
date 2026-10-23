package org.sopt.solply_server.domain.review.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.review.entity.PlaceReview;
import org.sopt.solply_server.domain.review.entity.VisitTime;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;

/**
 * 내 기록 목록 한 칸을 만드는 매핑만 확인한다.
 * <p>
 * 앱이 칸을 눌러 장소 상세로 가려면 placeId가 응답에 있어야 하는데,
 * 이 값은 {@code from} 한 곳에서만 실린다.
 */
class MyReviewPreviewItemTest {

  private static final long REVIEW_ID = 7L;
  private static final long PLACE_ID = 42L;

  @Test
  void from은_리뷰의_장소_id를_싣는다() {
    PlaceReview review = review(List.of());
    ImageUrlProvider imageUrlProvider = mock(ImageUrlProvider.class);

    MyReviewPreviewItem item = MyReviewPreviewItem.from(review, imageUrlProvider);

    assertThat(item.reviewId()).isEqualTo(REVIEW_ID);
    assertThat(item.placeId()).isEqualTo(PLACE_ID);
    assertThat(item.placeName()).isEqualTo("혼자 가기 좋은 카페");
    assertThat(item.content()).isEqualTo("열 글자가 넘는 정상적인 기록 내용입니다.");
    assertThat(item.previewImageUrl()).isNull();
  }

  @Test
  void from은_첫_이미지만_URL로_바꾼다() {
    PlaceReview review = review(List.of("reviews/first.jpg", "reviews/second.jpg"));
    ImageUrlProvider imageUrlProvider = mock(ImageUrlProvider.class);
    given(imageUrlProvider.getImageUrl("reviews/first.jpg"))
        .willReturn("https://cdn.example.com/reviews/first.jpg");

    MyReviewPreviewItem item = MyReviewPreviewItem.from(review, imageUrlProvider);

    assertThat(item.previewImageUrl()).isEqualTo("https://cdn.example.com/reviews/first.jpg");
    verify(imageUrlProvider).getImageUrl("reviews/first.jpg");
    verify(imageUrlProvider, never()).getImageUrl("reviews/second.jpg");
  }

  private static PlaceReview review(List<String> imageFileKeys) {
    PlaceReview review = PlaceReview.create(
        null,
        place(),
        LocalDate.of(2026, 7, 1),
        VisitTime.EVENING,
        "열 글자가 넘는 정상적인 기록 내용입니다.",
        4);
    setId(PlaceReview.class, review, REVIEW_ID);
    review.replaceImages(imageFileKeys);
    return review;
  }

  private static Place place() {
    Place place = Place.create(
        "혼자 가기 좋은 카페", "소개", "주소", 37.5, 127.0, null, null,
        Map.of(), List.of(), List.of(), null, null, true, null, List.of(), List.of());
    setId(Place.class, place, PLACE_ID);
    return place;
  }

  /** id는 DB가 정하므로 빌더가 없는 엔티티에는 리플렉션 말고 심을 자리가 없다 */
  private static void setId(Class<?> type, Object entity, long id) {
    try {
      var field = type.getDeclaredField("id");
      field.setAccessible(true);
      field.set(entity, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
