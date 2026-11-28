package com.teaplayer.teahome.api.watch;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teaplayer.teahome.auth.TokenAuthFilter;
import com.teaplayer.teahome.auth.TokenPrincipal;

/**
 * 观看进度跨端：`GET /v1/progress` 拉、`POST` 推一条、`DELETE` 删（写墓碑）。
 *
 * <p>不做增量游标：单用户片库就几百条，全量拉的 body 比一次条件请求还小，
 * 客户端自己按 `updatedAt` 对账更简单。
 */
@RestController
@RequestMapping("/v1/progress")
public class ProgressController {

    private final WatchSyncService service;

    public ProgressController(WatchSyncService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<WatchDtos.ProgressList> list(
            @RequestAttribute(name = TokenAuthFilter.ATTRIBUTE, required = false)
            TokenPrincipal principal) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.listProgress(WatchAccess.tokenId(principal)));
    }

    @PostMapping
    public ResponseEntity<WatchDtos.ProgressItem> push(
            @RequestBody(required = false) WatchDtos.ProgressItem body,
            @RequestAttribute(name = TokenAuthFilter.ATTRIBUTE, required = false)
            TokenPrincipal principal) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.pushProgress(WatchAccess.tokenId(principal), body));
    }

    /** `?key=<sourceId>|<itemId>` 删一条；不带 key 表示清空。 */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> delete(
            @RequestParam(required = false) String key,
            @RequestAttribute(name = TokenAuthFilter.ATTRIBUTE, required = false)
            TokenPrincipal principal) {
        int rows = service.deleteProgress(WatchAccess.tokenId(principal), key);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("deleted", rows));
    }
}
