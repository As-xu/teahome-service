package com.teaplayer.teahome.crawl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** 一次性执行入口：docker compose run teahome-crawl。 */
@Component
@ConditionalOnProperty(prefix = "teahome.crawl", name = "once")
public class CrawlOnceRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CrawlOnceRunner.class);

    private final CrawlService service;
    private final CrawlProperties props;
    private final ConfigurableApplicationContext context;

    public CrawlOnceRunner(CrawlService service, CrawlProperties props, ConfigurableApplicationContext context) {
        this.service = service;
        this.props = props;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        String kind = props.getOnce();
        int code;
        try {
            CrawlService.RunOutcome outcome = switch (kind) {
                case "hourly" -> service.run("hourly", "cli");
                case "daily" -> service.run("daily", "cli");
                case "retention" -> service.retention("cli");
                default -> throw new IllegalArgumentException("未知的 teahome.crawl.once=" + kind);
            };
            log.info("一次性任务完成 kind={} status={} items={} upserted={}",
                    kind, outcome.status(), outcome.itemsFetched(), outcome.itemsUpserted());
            code = "failed".equals(outcome.status()) ? 1 : 0;
        } catch (RuntimeException e) {
            log.error("一次性任务失败 kind={}", kind, e);
            code = 1;
        }
        final int exitCode = code;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }
}
