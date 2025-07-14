package org.sopt.solply_server.domain.course.dto.response;

import lombok.Builder;
import org.sopt.solply_server.domain.course.dto.CourseFolderDto;

import java.util.List;

@Builder
public record CourseFolderPreviewListGetResponse(
        List<CourseFolderDto> folders
) {
    public static CourseFolderPreviewListGetResponse from(List<CourseFolderDto> folders) {
        return CourseFolderPreviewListGetResponse.builder()
                .folders(folders)
                .build();
    }
}