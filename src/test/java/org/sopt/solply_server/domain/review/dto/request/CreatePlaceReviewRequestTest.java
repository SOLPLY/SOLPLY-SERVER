package org.sopt.solply_server.domain.review.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.solply_server.domain.review.entity.VisitTime;

class CreatePlaceReviewRequestTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  private CreatePlaceReviewRequest requestWithRating(Integer rating) {
    return new CreatePlaceReviewRequest(
        1L,
        LocalDate.of(2026, 7, 1),
        VisitTime.EVENING,
        "열 글자가 넘는 정상적인 기록 내용입니다.",
        List.of(),
        rating);
  }

  private Set<String> violatedFields(CreatePlaceReviewRequest request) {
    return VALIDATOR.validate(request).stream()
        .map(ConstraintViolation::getPropertyPath)
        .map(Object::toString)
        .collect(Collectors.toUnmodifiableSet());
  }

  @Test
  void 평점이_없으면_검증에_실패한다() {
    assertThat(violatedFields(requestWithRating(null))).contains("rating");
  }

  @Test
  void 평점이_1_미만이면_검증에_실패한다() {
    assertThat(violatedFields(requestWithRating(0))).contains("rating");
  }

  @Test
  void 평점이_5_초과면_검증에_실패한다() {
    assertThat(violatedFields(requestWithRating(6))).contains("rating");
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void 평점_1에서_5는_검증을_통과한다(int rating) {
    assertThat(violatedFields(requestWithRating(rating))).isEmpty();
  }
}
