package org.sopt.solply_server.global.external.discord;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.external.discord.dto.DiscordMessageDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordNotificationService {

    @Value("${discord.webhook.url}") // application.yml에 등록된 url
    private String webhookUrl;

    @Value("${discord.enabled:false}") // 기본값은 false
    private boolean isDiscordEnabled;

    private final UserRepository userRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    @Async // 회원가입 로직이 멈추지 않게 비동기로 실행
    public void sendRegistrationMessage(User user, SocialPlatform platform) {
        if (!isDiscordEnabled) return;

        try {
            // 1. 가입 시간
            String joinTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

            // 2. 총 가입자 수 조회 (탈퇴하지 않은 유저만 카운트)
            long totalCount = userRepository.countByActiveTrue();

            DiscordMessageDto message = DiscordMessageDto.newUser(
                    "솔플러_" + user.getId(),
                    user.getEmail(),
                    platform.name(),
                    joinTime,
                    totalCount
            );

            restTemplate.postForEntity(webhookUrl, message, String.class);
        } catch (Exception e) {
            log.error("디스코드 알림 전송 실패: {}", e.getMessage());
        }
    }
}