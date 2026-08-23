package org.sopt.solply_server.domain.place.config;

import jakarta.validation.constraints.NotBlank;
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
 * 인기순 복합 점수 튜닝 파라미터.
 *
 * <pre>
 * score = w₁ · ln(1 + Σ북마크 2^(−경과일 / H)) + w₂ · (조정평점 − C)
 * 조정평점 = (Σ평점 + m·C) / (리뷰 수 + m),   C = 전체 평균 평점
 * </pre>
 *
 * <p><b>두 축의 치역이 다르다는 것이 이 파라미터들의 존재 이유다.</b> 북마크 축은 건수에 비례해
 * 상한이 없고 리뷰 축은 평점 범위에 갇혀 있다. 그대로 더하면 북마크가 리뷰를 완전히 압도하므로
 * 북마크 쪽에 로그를 씌워 자릿수를 맞춘 뒤 {@code w₂}로 힘을 배분한다. 로그는 부수 효과로
 * 반감기의 의미를 더 읽기 좋게 만든다 — 곱셈이 덧셈이 되어 <b>반감기 한 번 = 북마크 절반</b>이
 * 그대로 같은 크기의 감점(ln2 ≈ 0.693)이 된다.
 *
 * <p>가중치와 반감기를 코드에 박지 않는 이유는 이 값들이 "정답"이 아니라 서비스 성격에 따라
 * 조정될 값이기 때문이다. 반감기 90일은 "역대 인기"와 "최근 급상승 Top N"의 경계를 지키려고
 * 고른 값이고(30일 이하면 트렌딩이 된다), 배치 주기 역시 트렌드 민감도 요구가 생기면
 * 재배포 없이 당길 수 있어야 한다.
 *
 * <p><b>아래 기본값이 저장소에 남는 유일한 선언이다.</b> application.yml은 .gitignore의 {@code *.yml}에
 * 걸려 커밋되지 않으므로(시크릿이 들어 있다) yml에 적은 값은 각자의 로컬/배포 환경에만 존재한다.
 * yml에 {@code solply.place-stats.*}를 선언하면 여기 값을 덮어쓴다.
 *
 * <p><b>그래서 {@code @Validated}가 필수다.</b> yml이 환경마다 따로 놀기 때문에 한 환경에서만 난
 * 오타가 그 환경의 랭킹을 통째로 망가뜨려도 아무도 모른다. 특히 {@code half-life-days: 0}은 실측상
 * <em>조용히</em> 실패한다 — {@code POW(0.5, x / 86400.0 / 0.0)}이
 * {@code Warning 1365 Division by 0} → {@code NULL} → {@code SUM(NULL) = NULL}이 되고
 * 집계 쿼리의 {@code COALESCE(score, 0)}이 그 NULL을 0으로 삼켜, 예외도 로그도 없이 전 장소 점수가
 * 0이 된다. 음수를 넣으면 감쇠가 증폭으로 뒤집혀 한 장소 점수가 894,697까지 튀는 것도 확인했다.
 * 아래 제약으로 <b>부팅 시점에 죽게</b> 만들어, 잘못된 값이 배치까지 살아 내려가지 못하게 한다.
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "solply.place-stats")
public class PlaceStatsProperties {

    /**
     * 표시 카운트 배치의 cron. 매시 30분 — 근거는 {@code PlaceStatsFacade} javadoc에 있다.
     *
     * <p><b>⚠️ 이 필드의 값은 스케줄에 쓰이지 않는다 — {@code getCountCron()} 호출처가 0건이다.</b>
     * 스케줄러는 {@code PlaceStatsFacade}의
     * {@code @Scheduled(cron = "${solply.place-stats.count-cron:0 30 * * * *}")}로 프로퍼티를 직접 읽는다.
     * {@code @ConfigurationProperties}의 필드 기본값은 플레이스홀더 해석 시점에 보이지 않으므로
     * <b>기본값 리터럴이 두 곳에 존재하는 것은 구조적으로 강제된 중복</b>이다.
     * <b>주기를 바꿀 때는 반드시 두 곳을 함께 고칠 것</b> — 이 필드만 고치면 스케줄은 그대로인
     * 방향으로 조용히 갈라진다. {@code PlaceStatsFacadeTest}가 두 리터럴을 묶어 감시한다.
     *
     * <p>그럼에도 필드를 남기는 이유는 <b>진단 가능한 실패</b>다. yml에 {@code count-cron: ""}이
     * 들어오면 {@code @NotBlank}가 "비었다"고 명시하며 부팅을 막는다. 이 필드가 없으면 빈 문자열이
     * 플레이스홀더로 해석돼 {@code @Scheduled}까지 내려가는데, 스프링은 그때 스케줄을 등록하지 않고
     * (spring-context 6.1.14 바이트코드 확인: 해석 결과가 빈 문자열이면 등록 분기를 건너뛴다)
     * 결국 {@code IllegalArgumentException: One-time task only supported with specified initial delay}로
     * 부팅이 죽는다 — cron과 아무 상관없어 보이는 메시지라 원인 추적이 훨씬 어렵다.
     */
    @NotBlank
    private String countCron = "0 30 * * * *";

    /**
     * 카운트 안전망 배치의 cron. 매일 01:45 (KST) — 표시 카운트 셋을 원본에서 다시 세고 아웃박스를
     * 비우는 회차이고, 시각 선정 근거는 {@code PlaceStatsFacade} javadoc에 있다.
     *
     * <p>필드를 남기는 이유와 <b>기본값 리터럴이 두 곳에 존재하는 것이 강제된 중복</b>이라는 사실은
     * {@code countCron}과 같다 — 주기를 바꿀 때 두 곳을 함께 고칠 것.
     */
    @NotBlank
    private String countSafetyCron = "0 45 1 * * *";

    /**
     * 인기 점수 배치의 cron. 매일 01:00 (KST) — 존재 이유와 시각 선정 근거는 위 필드와 마찬가지로
     * {@code PlaceStatsFacade} javadoc에 있다.
     *
     * <p>시간대는 여기에 담지 않는다. {@code @Scheduled(zone = "Asia/Seoul")}이 고정값이고,
     * 프로퍼티로 빼면 "cron은 바꿨는데 zone은 안 바꿨다"는 갈림이 하나 더 생긴다.
     */
    @NotBlank
    private String scoreCron = "0 0 1 * * *";

    /**
     * 한 회차의 최대 시도 횟수 (초회 포함). 3이면 실패 시 두 번 더 돌린다.
     *
     * <p><b>재시도가 값어치 있는 이유는 회차 간격이 실패의 대가이기 때문이다.</b> 카운트 회차가
     * 한 번 죽으면 다음 회차까지 1시간, 점수 회차는 24시간 낡은 값이 남는다. 재시도가 없던
     * 시절에는 그 창을 통째로 감수했다.
     *
     * <p><b>전체를 다시 도는 것이 맞다 — 청크로 쪼개 실패 구간부터 재개하지 않는다.</b>
     * 2026-08-15 실측에서 카운트 회차가 3,514ms였다. 청킹은 범위 술어 때문에 매 회차 +22%를
     * 상시로 물고 실패 한 번에 1.4초쯤을 아끼므로, 본전이 되려면 회차마다 두 번씩 실패해야 한다.
     * 같은 측정에서 "배치가 어드민 쓰기를 막는다"는 청킹의 다른 근거도 무너졌다 —
     * 파생 테이블을 먼저 만들고 행을 잠그는 구간은 마지막 짧은 순간뿐이라 대기가 0이었다.
     * (원천 행 수가 자릿수로 늘면 다시 열린다: {@code docs/design/2026-08-15-bookmark-count-supply.md} 5장)
     *
     * <p><b>재시도가 안전한 근거는 멱등성이고, 그 멱등성은 {@code calculatedAt}을 고정할 때만
     * 성립한다.</b> 집계에 {@code created_at <= :calculatedAt} 상한이 있어 같은 기준 시각으로
     * 다시 돌리면 같은 결과가 나온다. 시도마다 시각을 새로 잡으면 그 사이 들어온 활동이 결과를
     * 바꾸므로 "재실행해도 안전하다"가 성립하지 않는다 — {@code PlaceStatsFacade}가 첫 시도의
     * 값을 모든 시도에 넘기는 이유다.
     *
     * <p>3을 넘겨 잡지 말 것. 실패가 일시적이지 않다면(스키마 불일치·데이터 오류) 몇 번을 돌려도
     * 같고, 시도 사이 대기가 {@code @Scheduled} 단일 스레드를 그만큼 붙잡는다.
     * {@code lockAtMostFor}(카운트 10분)도 최대 시도 시간을 담을 수 있어야 한다.
     */
    @Positive
    private int batchMaxAttempts = 3;

    /**
     * 시도 사이 대기. 있는 이유는 실패 직후 같은 자원을 곧바로 다시 치지 않기 위해서다 —
     * 락 경합이나 순간 장애가 가라앉을 틈을 준다.
     *
     * <p>정확한 값이 중요한 자리는 아니다. 회차 간격이 1시간·24시간인데 몇 초를 조정해서 달라지는
     * 것이 없다. 그럼에도 프로퍼티인 이유는 <b>0을 넣을 수 있어야 하기 때문이다</b> — 대기가 상수면
     * 실패 경로를 검증하는 테스트가 시도 횟수만큼 잠들고, 그 비용이 붙은 테스트는 결국 지워진다.
     *
     * <p>{@code batch-max-attempts}와 함께 회차 하나의 최장 시간을 정하므로
     * {@code lockAtMostFor}(카운트 10분) 안에 들어와야 한다.
     */
    @NotNull
    private Duration batchRetryDelay = Duration.ofSeconds(5);

    /**
     * 감쇠 반감기(일). 90일이면 30일 경과 시 79%, 1년 경과 시 6%가 남는다.
     * 0이면 0으로 나눠 전 장소 점수가 조용히 0이 되고, 음수면 감쇠가 증폭으로 뒤집힌다 — 양수만 허용한다.
     */
    @Positive
    private double halfLifeDays = 90.0;

    /**
     * 북마크 축(로그 압축된 감쇠 합)의 배율. <b>"북마크 1건의 기여분"이 아니다</b> —
     * 값이 곱해지는 자리가 {@code w₁ · ln(1 + Σ감쇠)}로 로그 <em>바깥</em>이다.
     *
     * <p>안에 넣지 않은 이유: {@code ln(1 + w₁·Σ)}이면 배율이 곡선의 휘어짐까지 건드려
     * 값을 올렸을 때 순위가 어떻게 움직일지 예측할 수 없게 된다. 밖에 두면 순수한 스케일이고
     * 0은 여전히 "북마크 축 끄기"로 정확히 동작한다. 0을 허용하고 음수만 막는 이유가 그것이다.
     */
    @PositiveOrZero
    private double bookmarkWeight = 1.0;

    /**
     * 리뷰 축(베이지안 조정 평점의 편차)의 배율. 실제 기여는
     * {@code w₂ · (조정평점 − 전체평균)}이라 <b>유계</b>다 — 전체 평균이 3.8이라면 −5.6 ~ +2.4.
     *
     * <p>2.0인 근거는 북마크 축과의 힘 배분이다. 로그 압축 뒤 북마크 11배 차이가 약 2.4점이라,
     * 리뷰가 충분히 쌓인 장소(v ≳ m)라면 최상 평점이 그 차이를 간신히 뒤집는다.
     * <b>리뷰가 1~2건뿐이면 뒤집지 못하는 것이 정상이다</b> — 적은 표본을 못 믿는 것이
     * 베이지안 평균을 도입한 이유 자체다.
     *
     * <p>범위가 음수 쪽으로 넓은 것도 의도다. 전체 평균이 중앙(3.0)보다 위에 있는 한
     * 나쁜 평점의 페널티가 좋은 평점의 보상보다 크다. 0은 리뷰 축을 끄는 유효한 설정이라
     * 허용하고, 음수는 평점의 부호를 뒤집어 나쁜 평가가 순위를 <em>올리게</em> 만들므로 막는다.
     */
    @PositiveOrZero
    private double reviewWeight = 2.0;

    /**
     * 베이지안 평균의 사전 표본 수 {@code m}. 리뷰가 이 건수일 때 자기 평점과 전체 평균이
     * 정확히 반반 섞이고, 리뷰가 없는 장소는 조정 평점이 전체 평균과 같아져 <b>기여가 정확히 0</b>이 된다.
     *
     * <p>{@code m}이 순위에 실제로 영향을 주는 구간은 장소당 리뷰 수가 {@code m}과 같은 자릿수일
     * 때뿐이다. 대부분의 장소가 리뷰 0~2건이면 무엇을 넣든 전부 전체 평균으로 눌리고, 반대로
     * 장소당 수십 건이면 섞이는 비중이 10% 아래라 역시 잘 보이지 않는다.
     * <b>운영 데이터의 장소당 리뷰 수 중앙값을 보고 재조정할 값이다</b> — 프로퍼티라 재배포가 필요 없다.
     *
     * <p>0을 막는 이유는 {@code half-life-days: 0}과 성질이 같다. 0이면 조정 평점이 그냥 자기
     * 평균이 되어 <b>리뷰 1건짜리 5점 장소가 리뷰 100건짜리 5점 장소와 같은 점수를 받는다</b> —
     * 예외도 로그도 없이 베이지안 보정만 통째로 사라진다. 게다가 리뷰가 0건인 장소는
     * {@code 0/0}이 되어 그 장소의 점수가 NULL로 죽는다.
     */
    @Positive
    private int minReviewCount = 5;
}
