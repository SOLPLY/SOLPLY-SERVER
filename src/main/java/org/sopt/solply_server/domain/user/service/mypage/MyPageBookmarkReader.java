package org.sopt.solply_server.domain.user.service.mypage;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.service.BookmarkService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MyPageBookmarkReader {

    private final BookmarkService bookmarkService;

    public Map<Long, Boolean> getPlaceBookmarkMap(Long userId, List<Long> placeIds) {
        return bookmarkService.getBookmarkStatusMap(userId, BookmarkTargetType.PLACE, placeIds);
    }

}