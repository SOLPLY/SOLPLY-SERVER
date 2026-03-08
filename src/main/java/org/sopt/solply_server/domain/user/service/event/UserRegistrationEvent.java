package org.sopt.solply_server.domain.user.service.event;

import org.sopt.solply_server.domain.user.entity.User;

public record UserRegistrationEvent(
        User user
) {
}