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

/** 收藏跨端。形状与 {@link ProgressController} 一致，只是字段不同。 */
@RestController
@RequestMapping("/v1/favorites")
public class FavoriteController {

    private final WatchSyncService service;

    public FavoriteController(WatchSyncService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<WatchDtos.FavoriteList> list(
            @RequestAttribute(name = TokenAuthFilter.ATTRIBUTE, required = false)
            TokenPrincipal principal) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.listFavorites(WatchAccess.tokenId(principal)));
    }

    @PostMapping
    public ResponseEntity<WatchDtos.FavoriteItem> push(
            @RequestBody(required = false) WatchDtos.FavoriteItem body,
            @RequestAttribute(name = TokenAuthFilter.ATTRIBUTE, required = false)
            TokenPrincipal principal) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.pushFavorite(WatchAccess.tokenId(principal), body));
    }

    /** `?key=<sourceId>|<itemId>` 删一条；不带 key 表示清空。 */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> delete(
            @RequestParam(required = false) String key,
            @RequestAttribute(name = TokenAuthFilter.ATTRIBUTE, required = false)
            TokenPrincipal principal) {
        int rows = service.deleteFavorite(WatchAccess.tokenId(principal), key);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("deleted", rows));
    }
}
