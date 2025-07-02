package org.sopt.solply_server.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI SOLPLY_API(){
        Info info=new Info()
                .title("솔플리_API")
                .description("SOPT 앱잼 - SOLPLY API입니다")
                .version("1.0");

        // 모든 엔드포인트에 JWT 인증 요구사항 적용 (추후 다시 고려. 필요에 따라 개별 API에 @SecurityRequirement 적용 가능)
        String jwtSchemeName = "JWTToken";
        SecurityRequirement securityRequirement = new SecurityRequirement().addList(jwtSchemeName);

        // JWT 인증을 위한 SecurityScheme 추가
        SecurityScheme bearerAuth = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .in(SecurityScheme.In.HEADER)
                .name("Authorization");

        return new OpenAPI()
                .addServersItem(new Server().url("/"))
                .info(info)
                .addSecurityItem(securityRequirement)
                .components(new Components().addSecuritySchemes(jwtSchemeName, bearerAuth));

    }

}