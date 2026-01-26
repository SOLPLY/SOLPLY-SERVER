package org.sopt.solply_server.domain.admin.tag.dto.response;

public record AdminTagActivationResponse(
        Long id,
        boolean active
) {
    public static AdminTagActivationResponse of(Long id, boolean active) {
        return new AdminTagActivationResponse(id, active);
    }
}