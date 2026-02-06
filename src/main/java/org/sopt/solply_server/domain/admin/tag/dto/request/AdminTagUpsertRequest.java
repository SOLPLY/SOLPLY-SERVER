package org.sopt.solply_server.domain.admin.tag.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.entity.TagUsage;
import org.sopt.solply_server.domain.user.entity.UserPersona;

public record AdminTagUpsertRequest(
        @NotNull TagType type,
        Long parentId,
        @NotBlank String name,
        @NotNull Boolean active,
        List<UserPersona> personas,
        @NotNull TagUsage usage
) {}
