package org.sopt.solply_server.global.config;

import org.sopt.solply_server.global.http.SocialPlatformRequestConverter;
import org.sopt.solply_server.global.util.StringToLongListConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new StringToLongListConverter());
        registry.addConverter(new SocialPlatformRequestConverter());
    }
}