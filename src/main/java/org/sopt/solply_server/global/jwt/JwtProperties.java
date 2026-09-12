package org.sopt.solply_server.global.jwt;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 서명·수명·고정 클레임.
 *
 * <p><b>여기 있는 값은 전부 "바꾸면 재로그인"이다.</b> 키·issuer·audience가 달라지면 이미 나간
 * 토큰은 전부 거절된다. 온라인 키 교체(옛 키로 한동안 더 받아 주기)는 구현하지 않았으므로,
 * 이 값들의 변경은 설정 수정이 아니라 전환 작업으로 다뤄야 한다.
 *
 * <p>아래 기본값이 저장소에 남는 유일한 선언이다 — application.yml은 .gitignore의 {@code *.yml}에
 * 걸려 커밋되지 않는다. 비밀 키만 기본값이 없고(부팅 시 죽는다) 나머지는 yml 없이도 동작한다.
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties("jwt")
public class JwtProperties {

    @NotBlank
    private String accessSecretKey;

    @NotBlank
    private String refreshSecretKey;

    /** 30분. 탈퇴·권한 변경이 반영되기까지의 상한이기도 하다 — 인증 경로가 DB를 읽지 않기 때문이다. */
    @Positive
    private long accessTokenExpireTime = 1_800_000L;

    /** 14일. 계열 하나가 회전 없이 버틸 수 있는 최대 기간이다. */
    @Positive
    private long refreshTokenExpireTime = 1_209_600_000L;

    /** 필수 클레임. 다른 서비스가 같은 키를 쓰게 되는 날 이 값이 토큰의 소속을 가른다. */
    @NotBlank
    private String issuer = "solply-server";

    @NotBlank
    private String audience = "solply-app";

    /**
     * 인스턴스 간 시계 차이를 흡수하는 폭. 0이면 앞선 시계가 발급한 토큰이 곧바로 거절된다.
     *
     * <p><b>만료에는 쓰이지 않는다.</b> 만료는 {@code exp <= now}로 정확히 본다
     * ({@code JwtTokenProvider#validateTimestamps}). 이 값이 완화하는 것은 미래 {@code iat}
     * 허용 폭과, 쓰지는 않지만 들어오면 파서가 보는 {@code nbf}다.
     */
    @PositiveOrZero
    private long clockSkewSeconds = 30L;

    /**
     * 수명은 <b>1초 이상</b>이어야 한다. NumericDate가 정수 초라 그보다 짧은 수명은 표현되지 않고,
     * {@code exp == iat}인 토큰이 만들어져 우리 검증({@code exp > iat})에 우리 토큰이 걸린다.
     *
     * <p>{@code @Positive}만으로는 못 잡는다 — 500(ms)은 양수지만 같은 초로 접힌다.
     */
    @AssertTrue(message = "jwt.access-token-expire-time과 refresh-token-expire-time은 1000ms 이상이어야 한다")
    public boolean isExpireTimeAtLeastOneSecond() {
        return accessTokenExpireTime >= 1_000L && refreshTokenExpireTime >= 1_000L;
    }
}
