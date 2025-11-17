package org.sopt.solply_server.domain.user.service.mypage;

import java.util.List;
import java.util.Map;

public interface PlaceBookmarkQueryService {
    Map<Long, Boolean> getBookmarkStatus(Long userId, List<Long> placeIds);
}
