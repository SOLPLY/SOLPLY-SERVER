package org.sopt.solply_server.domain.course.dto.response;

import lombok.Builder;
import org.sopt.solply_server.domain.course.dto.CourseFolderDto;

import java.util.List;

@Builder
public record CourseFolderPreviewGetResponse(
        List<CourseFolderDto> folders
) {
    public static CourseFolderPreviewGetResponse from(List<CourseFolderDto> folders) {
        return CourseFolderPreviewGetResponse.builder()
                .folders(folders)
                .build();
    }
}