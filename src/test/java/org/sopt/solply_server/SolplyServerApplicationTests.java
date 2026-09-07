package org.sopt.solply_server;

import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.cache.PlaceListSnapshotLoader;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
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

	/**
	 * test 프로파일에는 Flyway가 돌지 않는 빈 H2만 있어 place_stats 테이블 자체가 없다.
	 * 그대로 두면 PlaceStatsFacade의 ApplicationReadyEvent 리스너가 최초 적재를 시도하다 실패해
	 * 매 실행마다 ERROR 스택트레이스가 찍히고, contextLoads가 "기동 시 DB 경로가 깨져 있어도
	 * 통과하는" 스모크 테스트로 약해진다. 위 S3/Redis 목과 같은 이유(실인프라 없음)로 목을 세운다.
	 *
	 * <p>Mockito가 OptionalInt 반환에 OptionalInt.empty()를 돌려주므로 리스너는 생략 분기를 탄다.
	 * 프로퍼티 스위치로 리스너를 끄지 않는 이유: 운영에서 값이 잘못 세팅되면 백필이 조용히 꺼져
	 * 이 리스너가 존재하는 이유("배포 후 최대 24시간 전 장소 0점")가 에러 로그도 없이 재발한다.
	 */
	@MockBean
	PlaceStatsBatchProcessor placeStatsBatchProcessor;

	/**
	 * 같은 이유로 목록 스냅샷 로더도 목으로 세운다. {@code PlaceListSnapshotScheduler}의 기동
	 * 빌드는 {@code @PostConstruct}라 <b>싱글턴 초기화 중에 동기로</b> 돌고 예외를 잡지 않으므로,
	 * 테이블 없는 H2에서는 컨텍스트 기동 자체가 실패한다.
	 *
	 * <p>그 fail-fast는 운영에서 의도한 계약이다 — 스냅샷 없는 인스턴스가 트래픽을 받으면 목록이
	 * 통째로 비는 오답이 나간다({@code PlaceListSnapshotScheduler} javadoc). 그래서 프로퍼티로
	 * 끄는 대신 <b>여기서만</b> 의존을 끊는다: 스케줄러는 여전히 로더를 부르고 예외도 그대로 흘린다.
	 */
	@MockBean
	PlaceListSnapshotLoader placeListSnapshotLoader;

	@Test
	void contextLoads() {
	}

}
