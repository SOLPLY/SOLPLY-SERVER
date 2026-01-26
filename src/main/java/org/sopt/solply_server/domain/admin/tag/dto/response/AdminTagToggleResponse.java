package org.sopt.solply_server.domain.admin.tag.dto.response;

public record AdminTagToggleResponse(
        Long id,
        boolean active
) {
    public static AdminTagToggleResponse of(Long id, boolean active) {
        return new AdminTagToggleResponse(id, active);
    }
}