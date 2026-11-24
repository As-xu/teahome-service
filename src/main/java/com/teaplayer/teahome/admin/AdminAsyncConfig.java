package com.teaplayer.teahome.admin;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** One bounded worker for console-triggered crawls. */
@Configuration
@Profile("admin")
public class AdminAsyncConfig {

    @Bean(name = "adminExecutor")
    public Executor adminExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("admin-crawl-");
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }
}
