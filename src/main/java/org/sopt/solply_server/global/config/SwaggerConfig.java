package org.sopt.solply_server.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
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

        return new OpenAPI()
                .addServersItem(new Server().url("/"))
                .info(info)
                .components(new Components());

    }

}