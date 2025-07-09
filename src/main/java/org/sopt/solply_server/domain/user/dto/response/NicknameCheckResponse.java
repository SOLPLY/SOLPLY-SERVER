package org.sopt.solply_server.domain.user.dto.response;

public record NicknameCheckResponse(
        boolean isDuplicated
) {
    public static NicknameCheckResponse of(boolean isDuplicated) {
        return new NicknameCheckResponse(isDuplicated);
    }
}