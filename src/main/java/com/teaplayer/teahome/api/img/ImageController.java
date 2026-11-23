package com.teaplayer.teahome.api.img;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class ImageController {

    private final ImageService imageService;

    public ImageController(ImageService imageService) {
        this.imageService = imageService;
    }

    @GetMapping("/img/{key}")
    public ResponseEntity<byte[]> image(@PathVariable String key,
                                        @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        ImageService.Loaded loaded = imageService.load(key);
        String etag = "\"" + loaded.etag() + "\"";
        if (ifNoneMatch != null && ifNoneMatch.contains(loaded.etag())) {
            return ResponseEntity.status(304)
                    .eTag(etag)
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(loaded.contentType()))
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(loaded.bytes());
    }
}
