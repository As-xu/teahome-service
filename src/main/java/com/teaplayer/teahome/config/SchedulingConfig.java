package com.teaplayer.teahome.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 调度开关：migrate/一次性任务容器用 TEAHOME_SCHEDULING_ENABLED=false 关掉，避免进程不退出。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "teahome", name = "scheduling-enabled", matchIfMissing = true)
public class SchedulingConfig {
}
