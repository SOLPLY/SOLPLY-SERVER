package org.sopt.solply_server.global.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Profile("!test")
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableRedisRepositories
public class RedisConfig {

    private final RedisProperties redisProperties;

    /**
     * Redis 연결 설정
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // 단일 Redis 인스턴스 사용
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisProperties.getHost());
        config.setPort(redisProperties.getPort());
        config.setPassword(redisProperties.getPassword());

        // Lettuce 클라이언트 사용, Lettuce 클라이언트 세부 설정은 개발 단계에선 기본값 사용
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        return factory;
    }

    /**
     * 객체를 JSON으로 저장/조회하기 위한 ObjectMapper 설정 -> 엑세스 토큰 파싱 과정에서 문제가 생김
     */
//    @Bean
//    public ObjectMapper redisObjectMapper() {
//        ObjectMapper objectMapper = new ObjectMapper();
//        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY); // 직렬화/역직렬화 대상 설정
//        // 복원할 때 클래스 타입 정보를 포함하도록 설정
//        objectMapper.activateDefaultTyping(
//                LaissezFaireSubTypeValidator.instance,
//                ObjectMapper.DefaultTyping.NON_FINAL,
//                JsonTypeInfo.As.PROPERTY
//        );
//        objectMapper.registerModule(new JavaTimeModule()); // LocalDateTime, LocalDate 등 타입 지원
//        objectMapper.findAndRegisterModules();
//        return objectMapper;
//    }

    /**
     * 범용 RedisTemplate 설정
     */
    @Bean
    @Primary
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // Redis 전용 ObjectMapper를 메서드 내부에서 직접 생성
        ObjectMapper redisOnlyMapper = new ObjectMapper();
        redisOnlyMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        redisOnlyMapper.registerModule(new JavaTimeModule());
        redisOnlyMapper.findAndRegisterModules();

        // Redis 전용 ObjectMapper 사용
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisOnlyMapper);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        // 직렬화 설정
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(jsonSerializer);

        // 트랜잭션 지원 설정(기본적으로 atomic 연산, 롤백 지원)
        // ToDo: 트랜잭션 관리가 필요하다고 생각될 경우에 true로 설정
        template.setEnableTransactionSupport(false);

        template.afterPropertiesSet();

        log.info("Primary RedisTemplate 초기화 완료");
        return template;
    }
}