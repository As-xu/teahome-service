package com.teaplayer.teahome.config;

import com.teaplayer.teahome.auth.ApiTokenService;
import com.teaplayer.teahome.auth.TokenAuthFilter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class WebConfig {

    @Bean
    FilterRegistrationBean<TokenAuthFilter> tokenAuthFilter(ApiTokenService tokenService) {
        FilterRegistrationBean<TokenAuthFilter> registration =
                new FilterRegistrationBean<>(new TokenAuthFilter(tokenService));
        registration.addUrlPatterns("/v1", "/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
