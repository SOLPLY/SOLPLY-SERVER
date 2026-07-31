package org.sopt.solply_server.domain.bookmark.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkRepository;

@ExtendWith(MockitoExtension.class)
class BookmarkServiceTest {

    private static final LocalDateTime BOOKMARKED_AT = LocalDateTime.of(2026, 7, 30, 2, 0);

    @Mock
    private BookmarkRepository bookmarkRepository;

    @InjectMocks
    private BookmarkService bookmarkService;

    @Test
    void 내_북마크_시각_맵은_불변이다() {
        // 이 맵은 표시 카운트 보정(PlaceDisplayCount.correct)의 입력이다. 호출자가 수정하면
        // 보정 결과가 조용히 틀어지므로 컴파일이 아니라 실행이 막는다.
        given(bookmarkRepository.findMyBookmarkTimesByTargetIds(any(), any(), anyList()))
                // List.of(배열)은 varargs로 풀려 List<Object>가 되므로 타입 인자를 명시한다
                .willReturn(List.<Object[]>of(new Object[]{1L, BOOKMARKED_AT}));

        Map<Long, LocalDateTime> times = bookmarkService.getMyBookmarkTimesMap(
                7L, BookmarkTargetType.PLACE, List.of(1L));

        assertThat(times).containsExactly(Map.entry(1L, BOOKMARKED_AT));
        assertThatThrownBy(() -> times.put(2L, BOOKMARKED_AT))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
