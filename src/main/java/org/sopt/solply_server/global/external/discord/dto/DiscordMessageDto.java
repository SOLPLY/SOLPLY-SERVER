package org.sopt.solply_server.global.external.discord.dto;

import java.util.List;

public record DiscordMessageDto(
        String content,
        List<Embed> embeds
) {
    public static DiscordMessageDto newUser(
            String nickname, String email, String platform, String joinTime, long totalCount
    ) {
        return new DiscordMessageDto(
                "🎉 **새로운 솔플러가 합류했습니다!**",
                List.of(new Embed(
                        "신규 유저 가입 알림",
                        0x3498DB, // 파란색 코드
                        List.of(
                                new Field("닉네임", nickname, true),
                                new Field("플랫폼", platform, true),
                                new Field("이메일", email != null ? email : "비공개", false),
                                new Field("가입시간", joinTime, true),
                                new Field("총 가입자", totalCount + "명", true)
                        )
                ))
        );
    }

    public record Embed(String title, int color, List<Field> fields) {}
    public record Field(String name, String value, boolean inline) {}
}