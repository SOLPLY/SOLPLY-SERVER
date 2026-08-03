package org.sopt.solply_server.domain.place.service.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;

/**
 * 배치의 다중 인스턴스 중복 실행 방지 계약 — 같은 회차에 인스턴스 2대가 발화하는 상황을
 * <b>짧은 간격의 연속 호출 2회</b>로 모델링한다.
 *
 * <p>이 모델링이 성립하는 근거는 {@code lockAtLeastFor}다. 첫 실행이 6초 만에 끝나도 락은 1분간
 * 유지되므로, 그 안에 들어온 두 번째 호출은 락을 잡지 못하고 <b>블록 없이 건너뛴다</b>.
 * 실제 두 인스턴스가 같은 초에 발화하는 것과 구분되는 지점은 "동시성"뿐인데, ShedLock의 판정은
 * 동시성이 아니라 {@code shedlock} 행의 원자적 UPDATE 성공 여부라 결과가 같다. 스레드 2개로 진짜
 * 경합을 재현해도 검증되는 명제는 같고, 대신 플레이크가 들어온다.
 *
 * <p>{@code @SpringBootTest} 전체 컨텍스트인 이유: 검증 대상이 {@code @SchedulerLock} <b>AOP가
 * 실제로 프록시를 감쌌는가</b>이다. 슬라이스로 좁히면 그 배선이 빠져 아무것도 못 문다.
 *
 * <p><b>테스트가 하나인 이유 — 쪼개지 말 것.</b> 회차를 나누려면 {@code shedlock} 행을 지워야
 * 하는데, ShedLock은 "이 이름의 행은 이미 만들었다"를 {@code LockRecordRegistry}에 캐시해 두고
 * 그 뒤로는 INSERT를 건너뛰고 UPDATE만 시도한다. 행을 밖에서 지우면 UPDATE가 0행이 되어
 * <b>락을 영영 못 잡는</b> 상태가 되고, 그러면 두 번째 테스트가 검증이 아니라 그 부작용을 본다.
 */
@SpringBootTest
class PlaceStatsSchedulerLockIT extends MySqlContainerSupport {

    /** 락 이름 — 배포 단위 전체에서 유일해야 한다. {@code PlaceStatsFacade}와 반드시 같은 문자열. */
    private static final String LOCK_NAME = "place-stats-recalculate";

    /**
     * 메서드 이름은 베이스의 {@code datasource}와 반드시 달라야 한다({@code @DynamicPropertySource}는
     * static이라 동명이면 상위를 <em>숨긴다</em>).
     *
     * <p>배치 스케줄을 끄는 이유는 {@code PlaceListFlowIT}와 같다 — 스위트가 매시 30분을 지나면
     * 스케줄러가 배치를 돌려 아래의 호출 횟수 단언이 흔들린다. {@code "-"}는 스프링이
     * "등록하지 않음"으로 해석하는 센티널이다({@code Scheduled.CRON_DISABLED}).
     * <b>스케줄 등록과 {@code @SchedulerLock} AOP는 별개</b>라, 꺼도 락 프록시는 그대로 걸린다.
     */
    @DynamicPropertySource
    static void schedulerLockProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.cron", () -> "-");
    }

    @Autowired private PlaceStatsFacade facade;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 실행 횟수를 세는 지점. 파사드가 아니라 프로세서에 두는 이유는 파사드 메서드가 곧 락이 걸린
     * 지점이라 호출은 언제나 2회이고, 갈리는 것은 <b>본문이 돌았는가</b>이기 때문이다.
     *
     * <p>부팅 시 최초 적재({@code ApplicationReadyEvent})도 이 빈을 부르지만 그쪽은
     * {@code recalculateIfEmpty}라 아래 단언에 섞이지 않는다.
     */
    @SpyBean private PlaceStatsBatchProcessor processor;

    /**
     * 락 이름까지 함께 못 박는다. 실행 횟수만 보면 <b>어떤</b> 이름으로 잠갔는지 알 수 없어,
     * 이름이 다른 배치와 겹치도록 바뀌어도(그러면 서로의 회차를 잡아먹는다) 그린이다.
     */
    @Test
    void 같은_회차의_두_번째_호출은_락을_잡지_못해_건너뛴다() {
        facade.recalculatePlaceStats();
        facade.recalculatePlaceStats();

        verify(spiedProcessor(), times(1)).recalculateAll(any());

        List<String> lockNames = jdbcTemplate.queryForList("SELECT name FROM shedlock", String.class);
        assertThat(lockNames).containsExactly(LOCK_NAME);
    }

    /**
     * 스프링 AOP가 스파이를 다시 프록시로 감싸면 주입된 참조는 목이 아니게 되어 {@code verify}가
     * {@code NotAMockException}으로 죽는다. 감싸지 않은 경우 이 호출은 참조를 그대로 돌려준다.
     */
    private PlaceStatsBatchProcessor spiedProcessor() {
        return AopTestUtils.getUltimateTargetObject(processor);
    }

    /**
     * 이 IT는 롤백되지 않는다({@code @SpringBootTest}는 기본 커밋). 배치가 <b>모든 장소</b>에
     * place_stats 행을 남기므로 지우지 않으면 place_stats가 비어 있음을 전제하는 다른 IT가
     * 깨진다({@code PlaceStatsRepositoryIT}) — {@code PlaceStatsBatchProcessorIT}·
     * {@code PlaceListFlowIT}가 같은 이유로 같은 정리를 한다.
     *
     * <p>{@code shedlock} 행도 함께 지운다. {@code lockAtLeastFor} 1분 때문에 이 클래스가 끝난
     * 뒤에도 락이 남아, 같은 싱글턴 컨테이너를 쓰는 뒤 클래스가 배치를 부르면 조용히 건너뛴다.
     * (같은 JVM 안에서는 위 {@code LockRecordRegistry} 캐시가 남지만, 컨텍스트가 다르면 provider
     * 인스턴스도 다르므로 이 삭제가 뒤 클래스의 락 획득을 막지 않는다.)
     *
     * <p>{@code @AfterAll} + {@code DriverManager}인 이유는 static이라 {@code @Autowired}에
     * 닿을 수 없어서다. 이 클래스는 테스트 트랜잭션이 없어 모든 쓰기가 이미 커밋돼 있으므로
     * 두 번째 커넥션이 락을 기다릴 일도 없다.
     */
    @AfterAll
    static void cleanUpCommittedRows() throws Exception {
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate("DELETE FROM shedlock");
            // 배치는 place_stats뿐 아니라 세대 레지스터도 민다(V28). 값을 남기면 뒤 클래스가
            // "아직 배치가 안 돈" 상태를 전제할 수 없다. 1행 레지스터라 DELETE가 아니라 UPDATE다.
            st.executeUpdate("""
                    UPDATE place_stats_meta
                       SET current_generation = NULL, prev_generation = NULL
                     WHERE id = 1
                    """);
        }
    }
}
