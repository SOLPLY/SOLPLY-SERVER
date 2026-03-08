package org.sopt.solply_server.global.listener;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.user.service.event.UserRegistrationEvent;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.external.discord.dto.DiscordMessageDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserRegistrationEventListener {

    @Value("${discord.webhook.url}")
    private String webhookUrl;

    @Value("${discord.enabled:false}")
    private boolean isDiscordEnabled;

    private final UserRepository userRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT) // 커밋 완료 후 실행
    public void onUserRegistered(UserRegistrationEvent event) {
        if (!isDiscordEnabled) return;

        try {
            String joinTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            long totalCount = userRepository.countByActiveTrue();

            DiscordMessageDto message = DiscordMessageDto.newUser(
                    event.user().getId().toString(),
                    joinTime,
                    totalCount
            );

            restTemplate.postForEntity(webhookUrl, message, String.class);
        } catch (Exception e) {
            log.error("유저 가입 디스코드 알림 전송 실패", e);
        }
    }
}