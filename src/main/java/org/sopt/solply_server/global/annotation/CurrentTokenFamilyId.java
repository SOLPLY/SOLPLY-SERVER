package org.sopt.solply_server.global.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * 이 요청의 access 토큰이 속한 계열(로그인 1회) ID.
 *
 * <p>로그아웃이 끊을 대상을 정한다 — 사용자의 모든 기기가 아니라 이 토큰을 낸 로그인의 refresh만
 * 폐기한다. 인증되지 않은 요청에서는 {@code null}이고, 그 판정은 컨트롤러가 아니라 서비스가 한다.
 */
@Target({ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@AuthenticationPrincipal(expression = "#this instanceof T(org.sopt.solply_server.global.security.PrincipalDetails) ? #this.getFamilyId() : null")
public @interface CurrentTokenFamilyId {
}
