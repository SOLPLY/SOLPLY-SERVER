package org.sopt.solply_server.domain.place.service.facade;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.config.PlaceStatsProperties;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * <b>정기 회차를 여는 길이 cron 발화 넷뿐임을 고정한다 (2026-09-13).</b>
 *
 * <p>영속 회차 등록·워터마크·기한·복구 폴을 걷어낸 자리라, 되돌아오는 회귀는 큰 구조가 아니라
 * <b>메서드 하나</b>다 — 누군가 "밀린 회차를 메우자"며 {@code fixedDelay} 폴을 하나 더하면 그
 * 순간 회차가 두 경로로 갈리고, 그쪽 경로에는 ShedLock도 걸리지 않는다(복구 폴에 리더 선출을
 * 걸면 같은 사고에 함께 막히므로 걸 수도 없다). 그러면 <b>모든 인스턴스가 같은 회차를 겹쳐 도는</b>
 * 상태가 조용히 정상이 된다.
 *
 * <p><b>그래서 단언이 "넷이 있다"가 아니라 "넷뿐이다"다.</b> 무엇이 더해졌는지를 묻지 않고 개수와
 * 종류로 가둔다.
 *
 * <p>이 파일이 리플렉션만 쓰는 이유: 검증 대상이 <b>존재하지 않음</b>이라 스프링 컨텍스트를 띄워도
 * 볼 것이 늘지 않는다. 빈의 부재는 곧 클래스의 부재이고, 그것은 컴파일이 이미 증명한다 —
 * 삭제한 타입을 참조하면 빌드가 서니 그 자리에 테스트가 필요하지 않다.
 */
class PlaceStatsNoRecoveryPathTest {

    /** 유일한 정기 실행 경로. 이름까지 못 박아 "회차 하나를 폴로 갈아치우는" 변경을 걸러낸다 */
    private static final List<String> EXPECTED_SCHEDULED_METHODS = List.of(
            "consumeBookmarkCountDeltas",
            "recalculateReviewCounts",
            "recalculatePopularScores",
            "recalculatePlaceCountsSafety");

    @Test
    @DisplayName("파사드의 @Scheduled는 cron 넷뿐이고 고정 간격 폴은 하나도 없다")
    void 정기_실행_경로는_cron_발화_넷뿐이다() {
        List<Method> scheduled = Arrays.stream(PlaceStatsFacade.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Scheduled.class))
                .toList();

        assertThat(scheduled).hasSize(4);
        assertThat(scheduled.stream().map(Method::getName))
                .containsExactlyInAnyOrderElementsOf(EXPECTED_SCHEDULED_METHODS);
        for (Method method : scheduled) {
            Scheduled annotation = method.getAnnotation(Scheduled.class);
            assertThat(annotation.cron())
                    .as("%s는 cron 발화여야 한다", method.getName())
                    .isNotBlank();
            assertThat(annotation.fixedDelayString())
                    .as("%s에 고정 간격 폴이 붙었다 — 복구 폴이 되돌아오면 회차가 두 경로로 갈린다",
                            method.getName())
                    .isEmpty();
            assertThat(annotation.fixedRateString())
                    .as("%s에 고정 주기 폴이 붙었다", method.getName())
                    .isEmpty();
            assertThat(annotation.fixedDelay())
                    .as("%s에 고정 간격 폴이 붙었다", method.getName())
                    .isEqualTo(-1);
        }
    }

    /**
     * <b>복구 폴의 설정 키도 함께 사라졌다.</b> 필드를 지우지 않고 남겨 두면 yml에 그 키가 살아 있는
     * 환경에서 "설정은 했는데 아무 일도 일어나지 않는" 상태가 되고, 다음 사람은 폴이 도는 줄 안다.
     *
     * <p>{@code @ConfigurationProperties}는 모르는 키를 <b>조용히 무시</b>하므로 이 부재를 기동이
     * 알려주지 않는다 — 그래서 게터의 부재를 직접 묻는다.
     */
    @Test
    @DisplayName("recovery-poll-delay-ms 프로퍼티는 남아 있지 않다")
    void 복구_폴_설정_키는_사라졌다() {
        assertThat(Arrays.stream(PlaceStatsProperties.class.getMethods()).map(Method::getName))
                .as("복구 폴 관련 게터가 남아 있으면 걷어내기가 끝나지 않은 것이다")
                .noneMatch(name -> name.toLowerCase().contains("recoverypoll"));
    }
}
