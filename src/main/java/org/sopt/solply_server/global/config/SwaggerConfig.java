package org.sopt.solply_server.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    private static final String ACCESS_TOKEN_SCHEME = "AccessToken";
    private static final String REFRESH_TOKEN_SCHEME = "RefreshToken";

    /**
     * Swagger UI에 표시될 API 문서의 기본 정보 설정
     */
    private Info apiInfo() {
        return new Info()
                .title("SOLPLY API")
                .description("SOPT 앱잼 - SOLPLY API 문서입니다.")
                .version("1.0.0");
    }

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .addServersItem(new Server().url("/"))
                .info(apiInfo())
                .components(new Components()
                        .addSecuritySchemes(ACCESS_TOKEN_SCHEME, accessTokenSecurityScheme())
                        .addSecuritySchemes(REFRESH_TOKEN_SCHEME, refreshTokenSecurityScheme()))
                .security(List.of(
                        new SecurityRequirement().addList(ACCESS_TOKEN_SCHEME),
                        new SecurityRequirement().addList(REFRESH_TOKEN_SCHEME)
                ));
    }

    /**
     * AccessToken 인증 방식 설정
     */
    private SecurityScheme accessTokenSecurityScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .in(SecurityScheme.In.HEADER)
                .name("Authorization");
    }

    /**
     * RefreshToken 인증 방식 설정 (Header에 입력)
     */
    private SecurityScheme refreshTokenSecurityScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("Refresh-Token");
    }

}