package org.sopt.solply_server;

import org.junit.jupiter.api.Test;
import org.sopt.solply_server.global.cache.CacheService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@SpringBootTest
@ActiveProfiles("test")
class SolplyServerApplicationTests {

	@MockBean
	S3Presigner s3Presigner;

	@MockBean
	S3Client s3Client;

	@MockBean
	RedisTemplate<String, String> redisTemplate;

	@MockBean
	CacheService cacheService;

	@Test
	void contextLoads() {
	}

}
