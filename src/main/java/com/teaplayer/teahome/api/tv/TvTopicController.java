package com.teaplayer.teahome.api.tv;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手机 → TV 搜索关键词单槽。两个接口都挂在 `/v1/**` 下，因此和其余读接口
 * 一样受 {@code TokenAuthFilter} 与 {@code RateLimitFilter}（read 桶 30/s）保护。
 *
 * <p>刻意不用 `ETag`/`Last-Modified`：TV 是 3 秒一次的短轮询，条件请求的成本
 * 比 body 本身还高；真正的省流量手段是拿到 id 后不再拉。
 */
@RestController
@RequestMapping("/v1/tv")
public class TvTopicController {

    private final TvTopicService service;

    public TvTopicController(TvTopicService service) {
        this.service = service;
    }

    /** 手机把关键词投到电视。`{"topic":"繁花"}` → `{"id":12,"topic":"繁花","createdAt":"…"}`。 */
    @PostMapping("/search-topic")
    public ResponseEntity<TvTopicDtos.Topic> push(
            @RequestBody(required = false) TvTopicDtos.Push body) {
        String topic = body == null ? null : body.topic();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.push(topic));
    }

    /** TV 轮询：没有主题时返回全 null 的对象，而不是 204（客户端少一条分支）。 */
    @GetMapping("/search-topic/latest")
    public ResponseEntity<TvTopicDtos.Topic> latest() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.latest());
    }
}
