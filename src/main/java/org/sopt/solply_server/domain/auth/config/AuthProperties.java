package org.sopt.solply_server.domain.auth.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * refresh 회전 정책과 인증 임시 데이터의 수명.
 *
 * <p>JWT 자체의 계약({@code JwtProperties})과 갈라 둔다 — 이쪽 값은 바꿔도 이미 나간 토큰이
 * 무효가 되지 않는다. 유예를 늘리면 다음 회전부터 길어지고, 보존 기간을 줄이면 다음 정리
 * 회차부터 짧아질 뿐이다.
 *
 * <p>application.yml은 커밋되지 않으므로({@code *.yml}) 아래 기본값이 저장소에 남는 유일한
 * 선언이다. cron만은 {@code @Scheduled}의 플레이스홀더에도 같은 리터럴이 있어야 한다 —
 * {@code @ConfigurationProperties}의 필드 기본값은 플레이스홀더 해석 시점에 보이지 않는다
 * ({@code PlaceStatsProperties#countCron}이 같은 이유로 같은 중복을 안고 있다).
 * <b>주기를 바꿀 때는 {@code AuthTokenCleanupFacade}와 여기를 함께 고칠 것.</b>
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "solply.auth")
public class AuthProperties {

    /**
     * 회전 유예. 회전된 부모 토큰을 이 시간 동안 한 번 더 받아 주고, 그때는 <b>자식과 똑같은
     * refresh 문자열</b>을 돌려준다.
     *
     * <p>유예가 필요한 이유는 네트워크다. 새 토큰이 담긴 응답이 유실되면 클라이언트는 옛 토큰을
     * 그대로 들고 다시 요청하는데, 유예가 없으면 그것이 곧 "재사용"으로 읽혀 계정 전체가 끊긴다.
     * 3초는 재전송 한 번이 오갈 시간으로 잡은 값이고, 이 창이 열려 있는 동안은 옛 토큰으로도
     * <b>같은 자식 문자열</b>을 받아 갈 수 있다 — 창을 넓힐수록 재사용 감지가 늦어진다.
     *
     * <p>0으로 두면 유예 분기가 사라지는 것이 아니라 <b>즉시 재사용 판정</b>이 된다.
     */
    @NotNull
    private Duration rotationGrace = Duration.ofSeconds(3);

    /**
     * 계열 보존 기간. 계열 안의 <b>모든</b> 토큰이 만료되고 이만큼 더 지나야 계열째 삭제한다.
     *
     * <p>토큰 단위로 지우지 않는 이유는 이력이 재사용 판정의 근거이기 때문이다. 회전된 부모를
     * 먼저 지우면 그 부모로 들어온 재사용 요청이 "행이 없다"로 보여 전체 폐기가 일어나지 않는다.
     */
    @NotNull
    private Duration refreshRetention = Duration.ofDays(30);

    /** 어드민 OAuth state/nonce의 수명. 카카오 동의 화면을 넘기는 데 드는 시간이다. */
    @NotNull
    private Duration adminStateTtl = Duration.ofMinutes(10);

    /** 어드민 일회용 교환 코드의 수명. 리다이렉트 직후 바로 교환되므로 짧다. */
    @NotNull
    private Duration adminAuthCodeTtl = Duration.ofMinutes(5);

    /**
     * 음수 유예·음수 보존은 부팅에서 막는다.
     *
     * <p>둘 다 0은 뜻이 있는 값이다 — 유예 0은 "즉시 재사용 판정", 보존 0은 "만료 즉시 정리".
     * 음수는 그런 뜻이 없고 <b>조용히 뒤집힌다</b>: 유예가 음수면 회전 직후의 부모가 이미
     * 유예 종료 상태라 정상 재전송이 전체 폐기가 되고, 보존이 음수면 정리 기준 시각이 미래로
     * 밀려 아직 살아 있는 계열까지 삭제 대상에 들어온다.
     *
     * <p>{@code @Positive}는 {@code Duration}에 걸 수 없어 여기서 본다.
     */
    @AssertTrue(message = "solply.auth.rotation-grace와 refresh-retention은 음수일 수 없다")
    public boolean isGraceAndRetentionNonNegative() {
        return rotationGrace != null && !rotationGrace.isNegative()
                && refreshRetention != null && !refreshRetention.isNegative();
    }

    /**
     * 어드민 임시 데이터의 수명은 <b>0보다 커야 한다.</b> 0이나 음수면 발급하는 순간 이미
     * 만료라 소비 조건({@code expires_at > now})이 절대 참이 되지 않고, 어드민 로그인이 통째로
     * 막히면서 원인은 "state가 유효하지 않다"로만 보인다.
     */
    @AssertTrue(message = "solply.auth.admin-state-ttl과 admin-auth-code-ttl은 0보다 커야 한다")
    public boolean isAdminTtlPositive() {
        return adminStateTtl != null && !adminStateTtl.isZero() && !adminStateTtl.isNegative()
                && adminAuthCodeTtl != null && !adminAuthCodeTtl.isZero() && !adminAuthCodeTtl.isNegative();
    }

    /**
     * {@code oauth_nonce} 쿠키에 {@code Secure}를 붙일지. <b>기본이 {@code true}인 것이 계약이다</b> —
     * 이 쿠키는 state와 짝을 이뤄 콜백을 위조로부터 지키는 값이라 평문으로 나가면 안 된다.
     *
     * <p>{@code false}는 http로 도는 로컬 개발에서만 쓴다. 브라우저는 {@code Secure} 쿠키를
     * http 응답에서 저장하지 않으므로, 로컬에서 켜 두면 콜백이 언제나 nonce 없이 도착해
     * 어드민 로그인이 통째로 막힌다. 설정을 빠뜨렸을 때 <b>덜 안전한 쪽으로</b> 기울지 않도록
     * 기본값을 안전한 쪽에 두고 로컬에서만 내린다.
     */
    private boolean oauthNonceCookieSecure = true;

    /** 정리 배치의 cron. 매일 04:40 KST — 다른 배치(01:00·01:45·03:00·04:00)와 시각을 가른다. */
    @NotBlank
    private String cleanupCron = "0 40 4 * * *";

    /**
     * 정리 회차가 한 문장에서 건드리는 최대 행 수. 한 번에 다 지우면 그만큼의 행을 커밋까지
     * 잠그므로, 삭제할 것이 많은 회차를 여러 문장으로 나눈다.
     */
    @Positive
    private int cleanupBatchSize = 1_000;

    /** 정리 회차가 한 대상에서 반복할 최대 문장 수. 예상 밖으로 쌓였을 때 회차가 끝나기는 한다. */
    @Positive
    private int cleanupMaxBatches = 50;
}
