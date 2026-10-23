package org.sopt.solply_server.domain.place.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 동네 캐시의 설정값. 보관 기간·용량의 근거는
 * {@code load-test/campaigns/2026-09-24_town-retention-memory}다.
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "solply.place-list-town-cache")
public class PlaceListTownCacheProperties {

    /**
     * 항목 하나(동네, 번호)의 보관 기간. <b>적재해 넣은 시점부터</b> 세며, 최신·과거 번호를
     * 가리지 않는다. 읽어도 늘어나지 않는다.
     *
     * <p>65분 = 전 동네 번호를 올리는 매시 리뷰 배치 간격 + 탐색 여유 5분. 이 기간 안의 번호 수는
     * 15분 북마크 델타·일일 배치·관리자 수정까지 더해지므로 "2벌"이 보장되지 않는다.
     */
    @NotNull
    private Duration expireAfterWrite = Duration.ofMinutes(65);

    /**
     * 캐시가 들고 있을 <b>추정 바이트</b>의 상한. 항목 무게는
     * {@link org.sopt.solply_server.domain.place.cache.town.TownPlaces#estimatedBytes()}다.
     *
     * <p><b>힙의 엄격한 상한이 아니다.</b> 무게는 압축 참조 레이아웃을 가정한 보수적 추정이고,
     * 진행 중 요청이 잡고 있는 옛 참조, 적재 중인 후보, 아직 회수되지 않은 객체는 여기 들어가지
     * 않는다. 거리순이 서 있는 전역 스냅샷 한 벌도 따로 상주한다.
     */
    @Positive
    private long maxEstimatedBytes = 64L * 1024 * 1024;

    /**
     * 요청 <b>하나</b>가 적재를 기다리는 총 예산. 동네가 셋이어도 1초다 — 동네마다 이 값을 다시
     * 주면 관련 동네 수만큼 사용자 대기가 늘어난다.
     *
     * <p>예산을 넘기면 다른 번호의 데이터로 성공 응답하지 않고 재시도 가능 오류를 낸다.
     */
    @Positive
    private long requestBudgetMs = 1_000L;

    /**
     * 첫 페이지에서 <b>관측한 번호와 적재한 번호가 어긋났을 때</b> 다시 관측할 횟수의 상한.
     * 예산 안에서만 쓴다. 커서가 있는 요청은 재시도 대상이 아니다 — 그 경우의 정답은 만료다.
     */
    @Positive
    private int firstPageReobserveLimit = 2;

    /** 서버 사이에 나눠 쓰는 공유 사본(Redis). */
    @Valid
    @NotNull
    private Redis redis = new Redis();

    @Getter
    @Setter
    public static class Redis {

        /**
         * 끄면 공유 사본을 읽지도 싣지도 않는다 — 로컬 미스는 공유 사본이 없던 구조와 같이 최신
         * 번호만 DB로 복구한다. 접속 대상은 기존 {@code spring.data.redis.*}를 그대로 쓴다.
         */
        private boolean enabled = true;

        /** 전용 연결의 connect timeout. 요청 예산 안에서 DB 폴백 여유를 남기는 값이다. */
        @NotNull
        private Duration connectTimeout = Duration.ofMillis(200);

        /** 전용 연결의 명령 timeout. */
        @NotNull
        private Duration commandTimeout = Duration.ofMillis(200);

        /**
         * 공유 사본의 보관 기간. 처음 실은 시점부터 세며 중복 발행이 늘리지 않는다. 로컬 보관
         * 기간과 같은 65분으로 시작한 초기 설정이며, 최적값을 검증한 값이 아니다.
         */
        @NotNull
        private Duration payloadTtl = Duration.ofMinutes(65);

        /** 발행이 실패했을 때 곧바로 다시 해 보는 횟수. 그래도 실패하면 보완 대기로 남긴다. */
        @PositiveOrZero
        private int publishRetries = 1;

        /**
         * 보완 대기로 남길 키 수의 상한. 대기는 키만 들고, 로컬 보관 기간이 지나면 사라진다 —
         * 보완은 그 번호의 로컬 객체가 아직 있을 때만 일어난다.
         */
        @Positive
        private long pendingMaxEntries = 10_000;
    }
}
