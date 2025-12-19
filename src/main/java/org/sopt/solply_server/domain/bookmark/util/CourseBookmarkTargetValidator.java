package org.sopt.solply_server.domain.bookmark.util;


import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CourseBookmarkTargetValidator implements BookmarkTargetValidator {
    private final CourseRepository courseRepository;
    public BookmarkTargetType supports() { return BookmarkTargetType.COURSE; }
    public void validate(Long targetId) {
        if (!courseRepository.existsById(targetId)) throw new BusinessException(ErrorCode.NOT_FOUND_COURSE);
    }
}
