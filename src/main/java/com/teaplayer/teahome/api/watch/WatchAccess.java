package com.teaplayer.teahome.api.watch;

import com.teaplayer.teahome.api.ApiException;
import com.teaplayer.teahome.auth.TokenPrincipal;

/** 跨端片库接口的共用校验：token 归属。 */
final class WatchAccess {

    private WatchAccess() {
    }

    /** 返回 token id；没有可读写的主体时抛 403（`/v1/**` 已经过了令牌闸，这里是兜底）。 */
    static long tokenId(TokenPrincipal principal) {
        if (principal == null || !(principal.hasScope("read") || principal.hasScope("admin"))) {
            throw ApiException.forbidden("read scope required");
        }
        return principal.id();
    }
}
