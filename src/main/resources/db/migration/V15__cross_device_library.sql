-- P7 §5.2：收藏 / 观看进度跨端。
-- 归属单位是 token（一台手机 + 一台电视共用同一枚 token 就等于同一份片库），
-- 所以两张表都以 (token_id, item_key) 为主键，`item_key` 沿用客户端的
-- `"<sourceId>|<itemId>"`。
--
-- 删除用墓碑而不是物理删除：另一台设备下次拉取时才知道「这条被删了」，
-- 否则它会把本地旧的再推上来，删了又回来。`updated_at` 同时承担
-- 「谁更新」和「墓碑何时生效」两个角色，客户端只比这一个时间戳。
CREATE TABLE teaplayer_watch_progress (
    token_id     bigint      NOT NULL,
    item_key     text        NOT NULL,
    source_id    text        NOT NULL,
    item_id      text        NOT NULL,
    title        text        NOT NULL,
    line_index   integer     NOT NULL DEFAULT 0,
    episode_index integer    NOT NULL DEFAULT 0,
    episode_name text        NOT NULL DEFAULT '',
    position_ms  bigint      NOT NULL DEFAULT 0,
    duration_ms  bigint      NOT NULL DEFAULT 0,
    episodes     text        NOT NULL DEFAULT '',
    updated_at   timestamptz NOT NULL DEFAULT now(),
    deleted_at   timestamptz,
    CONSTRAINT pk_watch_progress PRIMARY KEY (token_id, item_key),
    CONSTRAINT fk_watch_progress__token FOREIGN KEY (token_id)
        REFERENCES teaplayer_api_token (id) ON DELETE CASCADE,
    CONSTRAINT ck_watch_progress__key CHECK (char_length(item_key) BETWEEN 3 AND 300),
    CONSTRAINT ck_watch_progress__title CHECK (char_length(title) BETWEEN 1 AND 300),
    CONSTRAINT ck_watch_progress__position CHECK (position_ms >= 0),
    CONSTRAINT ck_watch_progress__duration CHECK (duration_ms >= 0)
);
CREATE INDEX ix_watch_progress__token_updated
    ON teaplayer_watch_progress (token_id, updated_at DESC);
COMMENT ON TABLE teaplayer_watch_progress IS '按 token 的观看进度（跨端）；删除写墓碑，等各端同步过再清';

CREATE TABLE teaplayer_favorite (
    token_id    bigint      NOT NULL,
    item_key    text        NOT NULL,
    source_id   text        NOT NULL,
    source_name text        NOT NULL DEFAULT '',
    item_id     text        NOT NULL,
    title       text        NOT NULL,
    year        text,
    category    text,
    cover_url   text,
    remark      text,
    added_at    timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT pk_favorite PRIMARY KEY (token_id, item_key),
    CONSTRAINT fk_favorite__token FOREIGN KEY (token_id)
        REFERENCES teaplayer_api_token (id) ON DELETE CASCADE,
    CONSTRAINT ck_favorite__key CHECK (char_length(item_key) BETWEEN 3 AND 300),
    CONSTRAINT ck_favorite__title CHECK (char_length(title) BETWEEN 1 AND 300)
);
CREATE INDEX ix_favorite__token_updated ON teaplayer_favorite (token_id, updated_at DESC);
COMMENT ON TABLE teaplayer_favorite IS '按 token 的收藏（跨端）；删除写墓碑';

UPDATE teaplayer_service_meta
   SET meta_value = '13', updated_at = now()
 WHERE meta_key = 'schema_version';
