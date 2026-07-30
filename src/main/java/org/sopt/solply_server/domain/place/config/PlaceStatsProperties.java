package org.sopt.solply_server.domain.place.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 인기순 복합 점수 튜닝 파라미터.
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

    /** 배치 실행 cron. 장소 임베딩(03:00)·코스 임베딩(04:00)과 겹치지 않게 02:00. */
    @NotBlank
    private String cron = "0 0 2 * * *";

    /**
     * 감쇠 반감기(일). 90일이면 30일 경과 시 79%, 1년 경과 시 6%가 남는다.
     * 0이면 0으로 나눠 전 장소 점수가 조용히 0이 되고, 음수면 감쇠가 증폭으로 뒤집힌다 — 양수만 허용한다.
     */
    @Positive
    private double halfLifeDays = 90.0;

    /** 북마크 1건의 기본 가중치. 0은 북마크 축을 끄는 유효한 설정이라 허용하고, 음수만 막는다. */
    @PositiveOrZero
    private double bookmarkWeight = 1.0;

    /**
     * 리뷰 1건의 기본 가중치. 실제 기여는 (rating - 3)이 곱해져 -2배 ~ +2배 범위가 된다.
     * 0은 리뷰 축을 끄는 유효한 설정이라 허용한다. 음수는 평점의 부호를 뒤집어
     * 나쁜 평가가 순위를 <em>올리게</em> 만들므로 막는다.
     */
    @PositiveOrZero
    private double reviewWeight = 3.0;
}
