package com.teaplayer.teahome.api.feed;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

import com.teaplayer.teahome.config.ConfigService;

@RestController
@RequestMapping("/v1")
public class FeedController {

    private final FeedService feedService;
    private final ConfigService config;

    public FeedController(FeedService feedService, ConfigService config) {
        this.feedService = feedService;
        this.config = config;
    }

    @GetMapping("/feed")
    public ResponseEntity<FeedDtos.FeedResponse> feed(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        int effective = size == null
                ? config.intValue("feed.page_size_default", 24) : size;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePrivate())
                .body(feedService.feed(category, cursor, effective));
    }

    @GetMapping("/categories")
    public ResponseEntity<FeedDtos.CategoriesResponse> categories() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate())
                .body(feedService.categories());
    }
}
