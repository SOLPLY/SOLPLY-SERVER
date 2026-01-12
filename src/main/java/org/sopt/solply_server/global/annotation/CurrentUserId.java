package org.sopt.solply_server.global.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

// 현재 로그인한 사용자의 principal 객체에서 userId를 가져오는 어노테이션
@Target({ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@AuthenticationPrincipal(expression = "#this instanceof T(org.sopt.solply_server.global.security.PrincipalDetails) ? #this.getUserId() : null")
public @interface CurrentUserId {
}