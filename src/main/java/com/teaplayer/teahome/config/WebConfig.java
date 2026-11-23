package com.teaplayer.teahome.config;

import com.teaplayer.teahome.api.RateLimitFilter;
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
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter() {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
