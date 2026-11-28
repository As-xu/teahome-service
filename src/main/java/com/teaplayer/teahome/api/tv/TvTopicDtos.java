package com.teaplayer.teahome.api.tv;

import java.time.OffsetDateTime;

/** TV 搜索关键词推送的线格式。 */
public final class TvTopicDtos {

    private TvTopicDtos() {
    }

    /**
     * 手机推送的请求体。
     *
     * <p>`topic` 允许为空进来（由服务端给出 `bad_topic` 的稳定错误码），
     * 免得缺字段时变成 Jackson 的 500。
     */
    public record Push(String topic) {
    }

    /**
     * 单槽主题。`id`/`topic`/`createdAt` 三者同时为 null 表示「还没人推过」，
     * 手机和 TV 都按这个形状解析，不用处理 204 空 body。
     */
    public record Topic(Long id, String topic, OffsetDateTime createdAt) {

        public static Topic empty() {
            return new Topic(null, null, null);
        }
    }
}
