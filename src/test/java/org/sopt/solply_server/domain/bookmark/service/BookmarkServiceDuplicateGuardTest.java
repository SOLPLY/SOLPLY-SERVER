package org.sopt.solply_server.domain.bookmark.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.sopt.solply_server.domain.bookmark.entity.Bookmark;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkCountEventRepository;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkRepository;
import org.sopt.solply_server.domain.bookmark.util.BookmarkTargetValidator;
import org.sopt.solply_server.domain.bookmark.util.BookmarkTargetValidatorRegistry;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/**
 * 북마크 중복 등록 가드.
 *
 * <p>가드가 없던 시절 같은 대상을 두 번 누르면 유니크 제약 {@code uk_bookmark_user_target}이
 * 커밋 시점에 터지면서 500이 나갔다. 부하 테스트에서 성공률 미달의 원인으로 잡힌 그 결함을
 * 여기서 고정한다 — 중복은 <b>예외 없는 사고</b>가 아니라 409라는 정상 응답이어야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookmarkServiceDuplicateGuardTest {

    private static final Long USER_ID = 1L;
    private static final Long TARGET_ID = 100L;

    @Mock
    private BookmarkRepository bookmarkRepository;

    /** 카운트 전표 발행처. 발행 규칙 자체는 BookmarkCountEventPublishIT가 DB에서 문다. */
    @Mock
    private BookmarkCountEventRepository countEventRepository;

    @Mock
    private BookmarkTargetValidatorRegistry validatorRegistry;

    @Mock
    private BookmarkTargetValidator validator;

    @Mock
    private EntityLoader entityLoader;

    @InjectMocks
    private BookmarkService bookmarkService;

    @BeforeEach
    void stubCommonPath() {
        given(entityLoader.getUser(USER_ID)).willReturn(User.builder().build());
        given(validatorRegistry.validator(any(BookmarkTargetType.class))).willReturn(validator);
    }

    @Test
    void 이미_북마크한_장소를_다시_등록하면_409로_거절한다() {
        givenAlreadyBookmarked(BookmarkTargetType.PLACE);

        assertThatThrownBy(() -> bookmarkService.create(USER_ID, BookmarkTargetType.PLACE, TARGET_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_BOOKMARKED);
    }

    /** 클라이언트가 장소/코스를 코드로 구분할 수 있어야 하므로 코스는 코스 전용 코드를 쓴다. */
    @Test
    void 이미_북마크한_코스를_다시_등록하면_코스_전용_코드로_거절한다() {
        givenAlreadyBookmarked(BookmarkTargetType.COURSE);

        assertThatThrownBy(() -> bookmarkService.create(USER_ID, BookmarkTargetType.COURSE, TARGET_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_BOOKMARKED_COURSE);
    }

    /** 중복 응답은 반드시 409여야 한다. 400으로 내려가면 클라이언트가 "잘못된 요청"으로 오해한다. */
    @Test
    void 중복_북마크_코드는_두_종류_모두_409다() {
        assertThat(ErrorCode.ALREADY_BOOKMARKED.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.ALREADY_BOOKMARKED_COURSE.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    /** 중복이면 저장 자체가 없어야 한다 — 409를 던지고도 행이 하나 더 생기면 가드가 무의미하다. */
    @Test
    void 중복이면_저장하지_않는다() {
        givenAlreadyBookmarked(BookmarkTargetType.PLACE);

        assertThatThrownBy(() -> bookmarkService.create(USER_ID, BookmarkTargetType.PLACE, TARGET_ID))
                .isInstanceOf(BusinessException.class);

        verify(bookmarkRepository, never()).saveAndFlush(any(Bookmark.class));
    }

    /**
     * 동시 요청 둘은 exists 검사를 <em>둘 다</em> 통과할 수 있다. 그때 마지막 방어선인 유니크 제약이
     * 던지는 예외를 그대로 흘리면 500이다 — 앞선 검사와 같은 409로 되돌아와야 한다.
     */
    @Test
    void 동시_요청으로_유니크_제약이_터져도_500이_아니라_409다() {
        given(bookmarkRepository.existsByUserIdAndTargetTypeAndTargetId(
                USER_ID, BookmarkTargetType.PLACE, TARGET_ID)).willReturn(false);
        willThrow(new DataIntegrityViolationException("uk_bookmark_user_target"))
                .given(bookmarkRepository).saveAndFlush(any(Bookmark.class));

        assertThatThrownBy(() -> bookmarkService.create(USER_ID, BookmarkTargetType.PLACE, TARGET_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_BOOKMARKED);
    }

    /**
     * 가드가 정상 경로까지 막으면 안 된다. 아울러 저장은 {@code saveAndFlush}여야 한다 —
     * {@code save}로 되돌리면 제약 위반이 커밋 시점(이 메서드 밖)으로 밀려 다시 500이 된다.
     */
    @Test
    void 처음_등록하는_장소는_저장한다() {
        given(bookmarkRepository.existsByUserIdAndTargetTypeAndTargetId(
                USER_ID, BookmarkTargetType.PLACE, TARGET_ID)).willReturn(false);

        bookmarkService.create(USER_ID, BookmarkTargetType.PLACE, TARGET_ID);

        verify(bookmarkRepository).saveAndFlush(any(Bookmark.class));
    }

    /** 존재 검증은 중복 검사보다 먼저다 — 없는 장소를 중복이라고 답하면 안 된다. */
    @Test
    void 대상이_존재하지_않으면_중복_검사에_닿기_전에_실패한다() {
        willThrow(new BusinessException(ErrorCode.NOT_FOUND_PLACE))
                .given(validator).validate(TARGET_ID);

        assertThatThrownBy(() -> bookmarkService.create(USER_ID, BookmarkTargetType.PLACE, TARGET_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND_PLACE);

        verify(bookmarkRepository, never())
                .existsByUserIdAndTargetTypeAndTargetId(any(), any(), any());
    }

    private void givenAlreadyBookmarked(BookmarkTargetType type) {
        given(bookmarkRepository.existsByUserIdAndTargetTypeAndTargetId(USER_ID, type, TARGET_ID))
                .willReturn(true);
    }
}
