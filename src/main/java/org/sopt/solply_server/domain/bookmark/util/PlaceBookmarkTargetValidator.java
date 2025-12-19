package org.sopt.solply_server.domain.bookmark.util;


import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PlaceBookmarkTargetValidator implements BookmarkTargetValidator {
    private final PlaceRepository placeRepository;
    public BookmarkTargetType supports() { return BookmarkTargetType.PLACE; }
    public void validate(Long targetId) {
        if (!placeRepository.existsById(targetId)) throw new BusinessException(ErrorCode.NOT_FOUND_PLACE);
    }
}