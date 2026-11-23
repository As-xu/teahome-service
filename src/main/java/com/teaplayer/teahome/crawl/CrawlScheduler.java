package com.teaplayer.teahome.crawl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时入口：每小时增量、每日 04:10 巡检、每小时 :35 清理。 */
@Component
public class CrawlScheduler {

    private static final Logger log = LoggerFactory.getLogger(CrawlScheduler.class);

    private final CrawlService service;

    public CrawlScheduler(CrawlService service) {
        this.service = service;
    }

    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Shanghai")
    public void hourly() {
        try {
            service.run("hourly", "scheduler");
        } catch (RuntimeException e) {
            log.error("每小时抓取失败", e);
        }
    }

    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Shanghai")
    public void daily() {
        try {
            service.run("daily", "scheduler");
        } catch (RuntimeException e) {
            log.error("每日巡检失败", e);
        }
    }

    @Scheduled(cron = "0 35 * * * *", zone = "Asia/Shanghai")
    public void retention() {
        try {
            service.retention("scheduler");
        } catch (RuntimeException e) {
            log.error("过期清理失败", e);
        }
    }
}
