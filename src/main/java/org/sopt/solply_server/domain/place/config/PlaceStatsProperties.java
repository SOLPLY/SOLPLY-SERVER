package org.sopt.solply_server.domain.place.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "solply.place-stats")
public class PlaceStatsProperties {

    /** 배치 실행 cron. 장소 임베딩(03:00)·코스 임베딩(04:00)과 겹치지 않게 02:00. */
    private String cron = "0 0 2 * * *";

    /** 감쇠 반감기(일). 90일이면 30일 경과 시 79%, 1년 경과 시 6%가 남는다. */
    private double halfLifeDays = 90.0;

    /** 북마크 1건의 기본 가중치. */
    private double bookmarkWeight = 1.0;

    /** 리뷰 1건의 기본 가중치. 실제 기여는 (rating - 3)이 곱해져 -2배 ~ +2배 범위가 된다. */
    private double reviewWeight = 3.0;
}
