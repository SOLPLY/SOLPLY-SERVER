package org.sopt.solply_server.global.config;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableFeignClients(basePackages = "org.sopt.solply_server.global")
public class FeignConfig {

}