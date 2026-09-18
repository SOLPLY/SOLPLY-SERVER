package org.sopt.solply_server.domain.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.global.jwt.JwtProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 설정값이 <b>뜻이 뒤집히는</b> 자리에서 부팅을 막는지.
 *
 * <p>여기 있는 값들은 작아지는 것이 아니라 의미가 반대가 된다 — 음수 유예는 회전 직후의 부모를
 * 이미 유예 종료로 만들어 <b>정상 재전송을 전체 폐기로</b> 바꾸고, 음수 보존은 정리 기준 시각을
 * 미래로 밀어 <b>살아 있는 계열을 삭제 대상에</b> 넣는다. 증상이 "state가 유효하지 않다" 같은
 * 엉뚱한 모습으로만 보이므로, 부팅에서 죽는 편이 낫다.
 *
 * <p><b>제약 집합을 직접 검증하고, 그것이 부팅에 걸린다는 사실은 {@code @Validated}로 잇는다.</b>
 * 스프링은 같은 JSR-380 검증기를 바인딩 직후에 돌리므로 위반이 하나라도 있으면 컨텍스트가 뜨지
 * 않는다. 컨테이너를 띄우지 않고도 같은 제약을 재는 대신, 그 연결 고리를 아래에서 못 박는다.
 */
class AuthConfigValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    /** 이 연결이 없으면 아래 제약들이 <b>부팅을 막지 못한다</b> — 선언만 있고 검사는 없는 상태다. */
    @Test
    void 두_설정_클래스는_Validated다() {
        assertThat(AuthProperties.class.getAnnotation(Validated.class)).isNotNull();
        assertThat(JwtProperties.class.getAnnotation(Validated.class)).isNotNull();
    }

    @Test
    void 기본값만으로도_설정은_유효하다() {
        assertThat(validator.validate(new AuthProperties())).isEmpty();
        assertThat(validator.validate(jwt(properties -> {
        }))).isEmpty();
    }

    @Test
    void 음수_유예와_음수_보존은_거절된다() {
        assertThat(validator.validate(auth(p -> p.setRotationGrace(Duration.ofSeconds(-1)))))
                .isNotEmpty();
        assertThat(validator.validate(auth(p -> p.setRefreshRetention(Duration.ofDays(-1)))))
                .isNotEmpty();
        // 0은 뜻이 있는 값이다 — 유예 0은 "즉시 재사용 판정", 보존 0은 "만료 즉시 정리"
        assertThat(validator.validate(auth(p -> p.setRotationGrace(Duration.ZERO)))).isEmpty();
        assertThat(validator.validate(auth(p -> p.setRefreshRetention(Duration.ZERO)))).isEmpty();
    }

    /** 어드민 TTL이 0이면 발급 즉시 만료라 소비 조건이 절대 참이 되지 않는다. */
    @Test
    void 어드민_TTL은_0보다_커야_한다() {
        assertThat(validator.validate(auth(p -> p.setAdminStateTtl(Duration.ZERO)))).isNotEmpty();
        assertThat(validator.validate(auth(p -> p.setAdminAuthCodeTtl(Duration.ZERO)))).isNotEmpty();
        assertThat(validator.validate(auth(p -> p.setAdminStateTtl(Duration.ofMinutes(-1)))))
                .isNotEmpty();
    }

    @Test
    void 정리_cron과_덩어리_설정도_비거나_0일_수_없다() {
        assertThat(validator.validate(auth(p -> p.setCleanupCron(" ")))).isNotEmpty();
        assertThat(validator.validate(auth(p -> p.setCleanupBatchSize(0)))).isNotEmpty();
        assertThat(validator.validate(auth(p -> p.setCleanupMaxBatches(0)))).isNotEmpty();
    }

    /**
     * <b>{@code @Positive}만으로는 못 잡는다.</b> 500ms는 양수지만 NumericDate가 정수 초라
     * {@code exp}와 {@code iat}가 같은 초로 접히고, 그러면 {@code exp > iat} 검증에
     * <b>우리가 발급한 토큰이 걸린다</b>.
     */
    @Test
    void JWT_수명은_1초_미만일_수_없다() {
        assertThat(validator.validate(jwt(p -> p.setAccessTokenExpireTime(500)))).isNotEmpty();
        assertThat(validator.validate(jwt(p -> p.setRefreshTokenExpireTime(999)))).isNotEmpty();
        assertThat(validator.validate(jwt(p -> p.setAccessTokenExpireTime(1_000)))).isEmpty();
    }

    /** 비밀 키는 기본값이 없다 — 없으면 부팅이 죽는다. 값 자체는 여기서 읽지 않는다. */
    @Test
    void 서명_키가_없으면_거절된다() {
        JwtProperties properties = new JwtProperties();

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    /** issuer·audience는 토큰의 소속을 가르는 필수 클레임이라 비워 둘 수 없다. */
    @Test
    void issuer와_audience는_비울_수_없다() {
        assertThat(validator.validate(jwt(p -> p.setIssuer(" ")))).isNotEmpty();
        assertThat(validator.validate(jwt(p -> p.setAudience("")))).isNotEmpty();
        assertThat(validator.validate(jwt(p -> p.setClockSkewSeconds(-1)))).isNotEmpty();
    }

    private AuthProperties auth(Consumer<AuthProperties> mutation) {
        AuthProperties properties = new AuthProperties();
        mutation.accept(properties);
        return properties;
    }

    private JwtProperties jwt(Consumer<JwtProperties> mutation) {
        JwtProperties properties = new JwtProperties();
        // 키는 검증을 통과하기만 하면 되는 자리 표시자다 — 설정 파일의 실제 값을 읽지 않는다
        properties.setAccessSecretKey("placeholder-access");
        properties.setRefreshSecretKey("placeholder-refresh");
        mutation.accept(properties);
        return properties;
    }
}
