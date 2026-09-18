package org.sopt.solply_server.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.review.dto.request.CreatePlaceReviewRequest;
import org.sopt.solply_server.domain.review.entity.PlaceReview;
import org.sopt.solply_server.domain.review.entity.VisitTime;
import org.sopt.solply_server.domain.review.repository.PlaceReviewRepository;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.BusinessValidationException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * createReview의 평점 검증만 확인하는 최소 테스트.
 * <p>
 * @Valid를 거치지 않는 호출자가 생겨도 서비스가 스스로 잘못된 평점을 막아
 * DB CHECK 제약 위반(500)이 아니라 BusinessException(400)이 되는지 확인한다.
 * 평점이 아예 없는 요청은 한시적으로 허용하고 중립값 3으로 채운다.
 */
@ExtendWith(MockitoExtension.class)
class PlaceReviewServiceImplRatingValidationTest {

  @Mock
  private PlaceReviewRepository placeReviewRepository;

  @Mock
  private UserRepository userRepository;

  @Mock
  private PlaceRepository placeRepository;

  @Mock
  private ApplicationEventPublisher eventPublisher;

  @Mock
  private ImageUrlProvider imageUrlProvider;

  @InjectMocks
  private PlaceReviewServiceImpl placeReviewService;

  private CreatePlaceReviewRequest requestWithRating(Integer rating) {
    return new CreatePlaceReviewRequest(
        1L,
        LocalDate.of(2026, 7, 1),
        VisitTime.EVENING,
        "열 글자가 넘는 정상적인 기록 내용입니다.",
        List.of(),
        rating);
  }

  private void 유저와_장소는_존재한다() {
    given(userRepository.findById(anyLong())).willReturn(Optional.of(mock(User.class)));
    given(placeRepository.findActiveById(anyLong())).willReturn(Optional.of(mock(Place.class)));
  }

  // 한시 조치: 앱이 평점을 보내기 시작하면 다시 필수로 돌린다.
  @Test
  void 평점이_없으면_중립값_3으로_저장한다() {
    유저와_장소는_존재한다();
    given(placeReviewRepository.save(any(PlaceReview.class)))
        .willAnswer(invocation -> invocation.getArgument(0));

    placeReviewService.createReview(1L, requestWithRating(null));

    ArgumentCaptor<PlaceReview> saved = ArgumentCaptor.forClass(PlaceReview.class);
    verify(placeReviewRepository).save(saved.capture());
    assertThat(saved.getValue().getRating()).isEqualTo(3);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 6, 100})
  void 평점이_1에서_5_범위를_벗어나면_BusinessException을_던진다(int rating) {
    유저와_장소는_존재한다();

    assertThatThrownBy(() -> placeReviewService.createReview(1L, requestWithRating(rating)))
        .isInstanceOf(BusinessValidationException.class)
        .extracting(exception -> ((BusinessException) exception).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_PLACE_REVIEW_RATING);
  }

  @Test
  void 잘못된_평점은_저장을_시도하기_전에_막힌다() {
    유저와_장소는_존재한다();

    assertThatThrownBy(() -> placeReviewService.createReview(1L, requestWithRating(0)))
        .isInstanceOf(BusinessValidationException.class);

    verify(placeReviewRepository, never()).save(any(PlaceReview.class));
  }
}
