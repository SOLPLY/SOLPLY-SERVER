package org.sopt.solply_server.domain.place.service.job;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.config.PlaceStatsProperties;

/**
 * 정기 통계 회차의 종류. <b>회차마다 어느 설정 키를 읽는지가 여기 한 표에 모여 있는 것이 이 enum의
 * 값어치이고, 그것이 전부다</b> — 상태도, 저장되는 이름도 아니다.
 *
 * <p>재시도 설정이 회차별로 갈린 뒤(2026-09-12) 그 대응은 파사드 메서드 넷에 흩어져 있었고,
 * 새 키를 더하거나 옮길 때 빠뜨린 회차가 눈에 띄지 않았다.
 *
 * <p><b>cron 문자열을 여기서 읽을 수 있다고 {@code @Scheduled}의 리터럴이 사라지는 것은 아니다.</b>
 * 플레이스홀더 해석은 {@code @ConfigurationProperties}의 필드 기본값을 보지 못하므로 기본값
 * 리터럴은 두 곳에 있다 — 근거와 경고는 {@link PlaceStatsProperties#getCountCron()}에 있다.
 */
@Getter
@RequiredArgsConstructor
public enum PlaceStatsJobKind {

    /** 매시 :05 :20 :35 :50 — 리뷰 수·평균 평점 전량 재계산 */
    REVIEW_COUNT("인기순 리뷰 카운트",
            PlaceStatsProperties::getCountCron,
            PlaceStatsProperties::getReviewCountMaxAttempts,
            PlaceStatsProperties::getReviewCountRetryDelay),

    /** 매시 :00 :15 :30 :45 — 북마크 아웃박스 전표 소비 */
    BOOKMARK_DELTA("북마크 카운트 델타",
            PlaceStatsProperties::getBookmarkDeltaCron,
            PlaceStatsProperties::getBookmarkDeltaMaxAttempts,
            PlaceStatsProperties::getBookmarkDeltaRetryDelay),

    /** 매시 :10 — 인기 점수 채점 */
    POPULAR_SCORE("인기점수",
            PlaceStatsProperties::getScoreCron,
            PlaceStatsProperties::getBatchMaxAttempts,
            PlaceStatsProperties::getBatchRetryDelay),

    /** 매일 01:25 — 표시 카운트 셋 전량 재계산 + 아웃박스 비우기 */
    COUNT_SAFETY("인기순 카운트 안전망",
            PlaceStatsProperties::getCountSafetyCron,
            PlaceStatsProperties::getBatchMaxAttempts,
            PlaceStatsProperties::getBatchRetryDelay);

    /** 로그에 찍히는 이름. 회차 하나가 남기는 모든 줄이 이 값으로 시작한다 */
    private final String label;
    private final Function<PlaceStatsProperties, String> cronExpression;
    private final ToIntFunction<PlaceStatsProperties> maxAttempts;
    private final Function<PlaceStatsProperties, Duration> retryDelay;

    public String cron(PlaceStatsProperties properties) {
        return cronExpression.apply(properties);
    }

    public int maxAttempts(PlaceStatsProperties properties) {
        return maxAttempts.applyAsInt(properties);
    }

    public Duration retryDelay(PlaceStatsProperties properties) {
        return retryDelay.apply(properties);
    }
}
