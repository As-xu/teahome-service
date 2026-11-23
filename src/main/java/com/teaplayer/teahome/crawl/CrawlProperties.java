package com.teaplayer.teahome.crawl;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** P1 抓取参数；P3 配置中心上线后改成从 teaplayer_config_value 读取。 */
@ConfigurationProperties(prefix = "teahome.crawl")
public class CrawlProperties {

    /** 条目有效期，1–14 天。 */
    private int ttlDays = 8;
    /** 每小时增量窗口。 */
    private int hourlyWindowHours = 3;
    /** 每日巡检窗口。 */
    private int dailyWindowHours = 192;
    /** ac=detail 一次带多少个 id（实测 20 安全）。 */
    private int detailBatchSize = 20;
    /** 单源单轮最多翻多少页，防止源站 pagecount 异常时打爆。 */
    private int maxPages = 200;
    /** 请求间隔，礼貌抓取。 */
    private int requestIntervalMs = 300;
    /** 关掉后只停定时器，CLI 单次仍可跑。 */
    private boolean enabled = true;
    /** CLI 单次模式：hourly / daily / retention；只由命令行传入。 */
    private String once = "";

    public int getTtlDays() {
        return ttlDays;
    }

    public void setTtlDays(int ttlDays) {
        this.ttlDays = ttlDays;
    }

    public int getHourlyWindowHours() {
        return hourlyWindowHours;
    }

    public void setHourlyWindowHours(int hourlyWindowHours) {
        this.hourlyWindowHours = hourlyWindowHours;
    }

    public int getDailyWindowHours() {
        return dailyWindowHours;
    }

    public void setDailyWindowHours(int dailyWindowHours) {
        this.dailyWindowHours = dailyWindowHours;
    }

    public int getDetailBatchSize() {
        return detailBatchSize;
    }

    public void setDetailBatchSize(int detailBatchSize) {
        this.detailBatchSize = detailBatchSize;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public int getRequestIntervalMs() {
        return requestIntervalMs;
    }

    public void setRequestIntervalMs(int requestIntervalMs) {
        this.requestIntervalMs = requestIntervalMs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getOnce() {
        return once;
    }

    public void setOnce(String once) {
        this.once = once;
    }
}
