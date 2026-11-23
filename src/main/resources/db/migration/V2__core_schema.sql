-- V2: P0 核心 schema（21 张表 + 关键索引 + 审计不可变触发器）
-- 规范见 docs/DESIGN-teahome-data-config.md §1/§2；表名统一 teaplayer_ 前缀，
-- 约束/索引名去掉前缀后以 pk_/ux_/fk_/ck_/ix_ 开头。禁止 PG ENUM，统一 text + CHECK。

-- ---------------------------------------------------------------------------
-- 1. 源
-- ---------------------------------------------------------------------------
CREATE TABLE teaplayer_source (
    id                   text        NOT NULL,
    name                 text        NOT NULL,
    kind                 text        NOT NULL,
    base_url             text        NOT NULL,
    spec                 jsonb       NOT NULL,
    spec_version         integer     NOT NULL DEFAULT 1,
    allow_hosts          text[]      NOT NULL DEFAULT '{}',
    priority             smallint    NOT NULL DEFAULT 100,
    is_enabled           boolean     NOT NULL DEFAULT true,
    last_ok_at           timestamptz,
    last_error_at        timestamptz,
    last_error_code      text,
    last_error_detail    text,
    consecutive_failures smallint    NOT NULL DEFAULT 0,
    circuit_state        text        NOT NULL DEFAULT 'closed',
    circuit_open_until   timestamptz,
    avg_latency_ms       integer,
    revision             integer     NOT NULL DEFAULT 1,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    updated_by           text        NOT NULL DEFAULT 'system',
    deleted_at           timestamptz,
    CONSTRAINT pk_source PRIMARY KEY (id),
    CONSTRAINT ux_source__name UNIQUE (name),
    CONSTRAINT ck_source__id CHECK (id ~ '^[a-z][a-z0-9_]{1,31}$'),
    CONSTRAINT ck_source__kind CHECK (kind IN ('maccms', 'postgrest')),
    CONSTRAINT ck_source__base_url CHECK (base_url ~ '^https?://'),
    CONSTRAINT ck_source__priority CHECK (priority BETWEEN 0 AND 1000),
    CONSTRAINT ck_source__circuit_state CHECK (circuit_state IN ('closed', 'open', 'half_open')),
    CONSTRAINT ck_source__error_code CHECK (last_error_code IS NULL OR length(last_error_code) <= 64),
    CONSTRAINT ck_source__error_detail CHECK (last_error_detail IS NULL OR length(last_error_detail) <= 500)
);
CREATE INDEX ix_source__enabled_priority ON teaplayer_source (is_enabled, priority);
COMMENT ON TABLE teaplayer_source IS '源注册：声明式规格、健康与熔断状态；软删保留抓取历史';

-- ---------------------------------------------------------------------------
-- 2. 源站栏目树
-- ---------------------------------------------------------------------------
CREATE TABLE teaplayer_source_category (
    source_id          text        NOT NULL,
    type_id            text        NOT NULL,
    name               text        NOT NULL,
    parent_type_id     text,
    depth              smallint    NOT NULL DEFAULT 1,
    is_leaf            boolean     NOT NULL DEFAULT true,
    category_code      text,
    category_source    text        NOT NULL DEFAULT 'unmapped',
    classifier_version text,
    first_seen_at      timestamptz NOT NULL DEFAULT now(),
    last_seen_at       timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    raw                jsonb       NOT NULL DEFAULT '{}',
    CONSTRAINT pk_source_category PRIMARY KEY (source_id, type_id),
    CONSTRAINT fk_source_category__source FOREIGN KEY (source_id)
        REFERENCES teaplayer_source (id) ON DELETE CASCADE,
    CONSTRAINT ck_source_category__category CHECK (category_code IS NULL OR category_code IN
        ('movie', 'tv', 'anime', 'variety', 'documentary', 'short', 'other')),
    CONSTRAINT ck_source_category__source CHECK (category_source IN
        ('rule', 'detail_parent', 'manual', 'unmapped')),
    CONSTRAINT ck_source_category__depth CHECK (depth >= 1)
);
CREATE INDEX ix_source_category__parent ON teaplayer_source_category (source_id, parent_type_id);
CREATE INDEX ix_source_category__unmapped ON teaplayer_source_category (category_code)
    WHERE category_code IS NULL;
COMMENT ON TABLE teaplayer_source_category IS '源站栏目树与归一化结果；消失的栏目只变旧不删除';

-- ---------------------------------------------------------------------------
-- 3. 源内条目（核心表）
-- ---------------------------------------------------------------------------
CREATE TABLE teaplayer_source_item (
    id                 bigint      GENERATED ALWAYS AS IDENTITY,
    source_id          text        NOT NULL,
    item_id            text        NOT NULL,
    title              text        NOT NULL,
    title_norm         text        NOT NULL,
    merge_key          text        NOT NULL,
    merge_key_version  text        NOT NULL DEFAULT 'title-year-v1',
    year               smallint,
    category_codes     text[]      NOT NULL DEFAULT '{}',
    primary_category   text,
    category_raw       text,
    category_source    text        NOT NULL DEFAULT 'unmapped',
    classifier_version text        NOT NULL DEFAULT 'cat-v1',
    content_hash       text        NOT NULL,
    vod_time           timestamptz NOT NULL,
    expires_at         timestamptz NOT NULL,
    ttl_days_applied   smallint    NOT NULL DEFAULT 8,
    status             text        NOT NULL DEFAULT 'active',
    group_id           bigint,
    cover_url          text,
    cover_key          text,
    remark             text,
    area               text,
    lang               text,
    extra              jsonb       NOT NULL DEFAULT '{}',
    first_seen_at      timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_source_item PRIMARY KEY (id),
    CONSTRAINT ux_source_item__source_item UNIQUE (source_id, item_id),
    CONSTRAINT fk_source_item__source FOREIGN KEY (source_id)
        REFERENCES teaplayer_source (id) ON DELETE RESTRICT,
    CONSTRAINT ck_source_item__status CHECK (status IN ('active', 'gone')),
    CONSTRAINT ck_source_item__category_source CHECK (category_source IN
        ('rule', 'detail_parent', 'manual', 'unmapped')),
    CONSTRAINT ck_source_item__primary_category CHECK (primary_category IS NULL OR primary_category IN
        ('movie', 'tv', 'anime', 'variety', 'documentary', 'short', 'other')),
    CONSTRAINT ck_source_item__category_codes CHECK (category_codes <@ ARRAY[
        'movie', 'tv', 'anime', 'variety', 'documentary', 'short', 'other']::text[]),
    CONSTRAINT ck_source_item__content_hash CHECK (length(content_hash) = 64),
    CONSTRAINT ck_source_item__ttl CHECK (ttl_days_applied BETWEEN 1 AND 14),
    CONSTRAINT ck_source_item__expires CHECK (expires_at > first_seen_at)
);
CREATE INDEX ix_source_item__expires_at ON teaplayer_source_item (expires_at);
CREATE INDEX ix_source_item__group_id ON teaplayer_source_item (group_id);
CREATE INDEX ix_source_item__merge_key ON teaplayer_source_item (merge_key);
CREATE INDEX ix_source_item__updated_at ON teaplayer_source_item (updated_at DESC);
CREATE INDEX ix_source_item__source_live ON teaplayer_source_item (source_id, vod_time DESC)
    WHERE status = 'active';
CREATE INDEX ix_source_item__category_time ON teaplayer_source_item (primary_category, vod_time DESC, id DESC);
CREATE INDEX ix_source_item__category_codes ON teaplayer_source_item USING gin (category_codes);
COMMENT ON TABLE teaplayer_source_item IS '源内条目（物理唯一）；TTL 以 vod_time 为唯一基准，过期即硬删';

-- ---------------------------------------------------------------------------
-- 4. 合并组（一张卡）
-- ---------------------------------------------------------------------------
CREATE TABLE teaplayer_title_group (
    id                 bigint      GENERATED ALWAYS AS IDENTITY,
    merge_key          text        NOT NULL,
    merge_key_version  text        NOT NULL,
    title              text        NOT NULL,
    title_norm         text        NOT NULL,
    year               smallint,
    category_codes     text[]      NOT NULL DEFAULT '{}',
    primary_category   text        NOT NULL,
    category_source    text        NOT NULL,
    cover_url          text,
    cover_key          text,
    remark             text,
    primary_item_id    bigint,
    primary_source_id  text,
    item_count         smallint    NOT NULL DEFAULT 0,
    source_count       smallint    NOT NULL DEFAULT 0,
    vod_time_max       timestamptz NOT NULL,
    first_seen_at      timestamptz NOT NULL DEFAULT now(),
    last_seen_at       timestamptz NOT NULL DEFAULT now(),
    expires_at         timestamptz NOT NULL,
    classifier_version text        NOT NULL DEFAULT 'cat-v1',
    revision           integer     NOT NULL DEFAULT 1,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_title_group PRIMARY KEY (id),
    CONSTRAINT ux_title_group__merge_key UNIQUE (merge_key),
    CONSTRAINT fk_title_group__primary_item FOREIGN KEY (primary_item_id)
        REFERENCES teaplayer_source_item (id) ON DELETE SET NULL,
    CONSTRAINT ck_title_group__primary_category CHECK (primary_category IN
        ('movie', 'tv', 'anime', 'variety', 'documentary', 'short', 'other')),
    CONSTRAINT ck_title_group__category_source CHECK (category_source IN
        ('primary', 'override', 'rule')),
    CONSTRAINT ck_title_group__category_codes CHECK (category_codes <@ ARRAY[
        'movie', 'tv', 'anime', 'variety', 'documentary', 'short', 'other']::text[]),
    CONSTRAINT ck_title_group__counts CHECK (item_count >= 0 AND source_count >= 0)
);
CREATE INDEX ix_title_group__feed ON teaplayer_title_group (primary_category, vod_time_max DESC, id DESC);
CREATE INDEX ix_title_group__expires_at ON teaplayer_title_group (expires_at);
CREATE INDEX ix_title_group__last_seen ON teaplayer_title_group (last_seen_at DESC);
COMMENT ON TABLE teaplayer_title_group IS '跨源合并组：一卡一行，物化排序键支持游标分页';

ALTER TABLE teaplayer_source_item
    ADD CONSTRAINT fk_source_item__group FOREIGN KEY (group_id)
    REFERENCES teaplayer_title_group (id) ON DELETE SET NULL;

-- ---------------------------------------------------------------------------
-- 5-6. 播放线路与剧集
-- ---------------------------------------------------------------------------
CREATE TABLE teaplayer_play_line (
    id            bigint      GENERATED ALWAYS AS IDENTITY,
    item_id       bigint      NOT NULL,
    play_from     text        NOT NULL,
    line_index    smallint    NOT NULL DEFAULT 0,
    is_primary    boolean     NOT NULL DEFAULT false,
    protocol      text        NOT NULL DEFAULT 'unknown',
    episode_count integer     NOT NULL DEFAULT 0,
    primary_url   text,
    raw           jsonb       NOT NULL DEFAULT '{}',
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_play_line PRIMARY KEY (id),
    CONSTRAINT ux_play_line__item_from_idx UNIQUE (item_id, play_from, line_index),
    CONSTRAINT fk_play_line__item FOREIGN KEY (item_id)
        REFERENCES teaplayer_source_item (id) ON DELETE CASCADE,
    CONSTRAINT ck_play_line__protocol CHECK (protocol IN ('hls', 'page', 'direct', 'unknown')),
    CONSTRAINT ck_play_line__episode_count CHECK (episode_count >= 0)
);
CREATE INDEX ix_play_line__item ON teaplayer_play_line (item_id, is_primary DESC);
COMMENT ON TABLE teaplayer_play_line IS '播放线路；play_from 保留源站原文不改写';

CREATE TABLE teaplayer_episode (
    id            bigint      GENERATED ALWAYS AS IDENTITY,
    line_id       bigint      NOT NULL,
    episode_index integer     NOT NULL,
    name          text        NOT NULL,
    url           text        NOT NULL,
    url_host      text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_episode PRIMARY KEY (id),
    CONSTRAINT ux_episode__line_index UNIQUE (line_id, episode_index),
    CONSTRAINT fk_episode__line FOREIGN KEY (line_id)
        REFERENCES teaplayer_play_line (id) ON DELETE CASCADE,
    CONSTRAINT ck_episode__index CHECK (episode_index >= 1)
);
CREATE INDEX ix_episode__url_host ON teaplayer_episode (url_host);
COMMENT ON TABLE teaplayer_episode IS '剧集/分集地址；随线路级联删除';

-- ---------------------------------------------------------------------------
-- 7. 封面缓存元数据
-- ---------------------------------------------------------------------------
CREATE TABLE teaplayer_image_cache (
    image_key       text        NOT NULL,
    source_url      text        NOT NULL,
    source_host     text        NOT NULL,
    content_sha     text,
    bytes           integer     NOT NULL DEFAULT 0,
    content_type    text,
    width           integer,
    height          integer,
    file_path       text        NOT NULL,
    etag            text,
    last_modified   text,
    status          text        NOT NULL DEFAULT 'pending',
    fail_count      smallint    NOT NULL DEFAULT 0,
    last_error_code text,
    last_error_at   timestamptz,
    hit_count       bigint      NOT NULL DEFAULT 0,
    last_hit_at     timestamptz,
    fetched_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_image_cache PRIMARY KEY (image_key),
    CONSTRAINT ck_image_cache__key CHECK (length(image_key) = 64),
    CONSTRAINT ck_image_cache__content_sha CHECK (content_sha IS NULL OR length(content_sha) = 64),
    CONSTRAINT ck_image_cache__bytes CHECK (bytes >= 0),
    CONSTRAINT ck_image_cache__content_type CHECK (content_type IS NULL OR content_type LIKE 'image/%'),
    CONSTRAINT ck_image_cache__status CHECK (status IN ('pending', 'ok', 'failed', 'blocked'))
);
CREATE INDEX ix_image_cache__last_hit ON teaplayer_image_cache (last_hit_at);
CREATE INDEX ix_image_cache__status ON teaplayer_image_cache (status, last_error_at);
CREATE INDEX ix_image_cache__host ON teaplayer_image_cache (source_host);
COMMENT ON TABLE teaplayer_image_cache IS '封面缓存元数据；文件落在 /opt/teahome/images，库只存相对路径';

-- ---------------------------------------------------------------------------
-- 8-10. 抓取批次、每源任务、条目级重试
-- ---------------------------------------------------------------------------
CREATE TABLE teaplayer_crawl_run (
    id              bigint      GENERATED ALWAYS AS IDENTITY,
    run_kind        text        NOT NULL,
    window_from     timestamptz NOT NULL,
    window_to       timestamptz NOT NULL,
    scheduled_at    timestamptz,
    started_at      timestamptz,
    finished_at     timestamptz,
    status          text        NOT NULL DEFAULT 'pending',
    source_total    smallint    NOT NULL DEFAULT 0,
    source_ok       smallint    NOT NULL DEFAULT 0,
    source_failed   smallint    NOT NULL DEFAULT 0,
    pages           integer     NOT NULL DEFAULT 0,
    items_fetched   integer     NOT NULL DEFAULT 0,
    items_upserted  integer     NOT NULL DEFAULT 0,
    items_skipped   integer     NOT NULL DEFAULT 0,
    details_fetched integer     NOT NULL DEFAULT 0,
    failures        integer     NOT NULL DEFAULT 0,
    bytes_in        bigint      NOT NULL DEFAULT 0,
    duration_ms     integer,
    error_code      text,
    error_detail    text,
    triggered_by    text        NOT NULL,
    trace_id        text,
    config_hash     text,
    lock_owner      text,
    dedupe_key      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_crawl_run PRIMARY KEY (id),
    CONSTRAINT ux_crawl_run__dedupe_key UNIQUE (dedupe_key),
    CONSTRAINT ck_crawl_run__kind CHECK (run_kind IN
        ('hourly', 'daily', 'category_sync', 'retention', 'reclassify', 'backfill', 'manual', 'image_gc')),
    CONSTRAINT ck_crawl_run__status CHECK (status IN
        ('pending', 'running', 'ok', 'partial', 'failed', 'cancelled')),
    CONSTRAINT ck_crawl_run__window CHECK (window_to >= window_from),
    CONSTRAINT ck_crawl_run__config_hash CHECK (config_hash IS NULL OR length(config_hash) = 64)
);
CREATE INDEX ix_crawl_run__started ON teaplayer_crawl_run (started_at DESC);
CREATE INDEX ix_crawl_run__running ON teaplayer_crawl_run (status) WHERE status = 'running';
COMMENT ON TABLE teaplayer_crawl_run IS '一轮抓取；dedupe_key 可空唯一键同时表达「定时唯一、手工可重跑」';

CREATE TABLE teaplayer_crawl_task (
    id                bigint      GENERATED ALWAYS AS IDENTITY,
    run_id            bigint      NOT NULL,
    source_id         text        NOT NULL,
    task_kind         text        NOT NULL,
    status            text        NOT NULL DEFAULT 'pending',
    attempts          smallint    NOT NULL DEFAULT 0,
    pages_done        integer     NOT NULL DEFAULT 0,
    items_fetched     integer     NOT NULL DEFAULT 0,
    items_upserted    integer     NOT NULL DEFAULT 0,
    failures          integer     NOT NULL DEFAULT 0,
    cursor_state      jsonb       NOT NULL DEFAULT '{}',
    started_at        timestamptz,
    finished_at       timestamptz,
    next_retry_at     timestamptz,
    duration_ms       integer,
    last_error_code   text,
    last_error_detail text,
    CONSTRAINT pk_crawl_task PRIMARY KEY (id),
    CONSTRAINT ux_crawl_task__run_source_kind UNIQUE (run_id, source_id, task_kind),
    CONSTRAINT fk_crawl_task__run FOREIGN KEY (run_id)
        REFERENCES teaplayer_crawl_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_crawl_task__source FOREIGN KEY (source_id)
        REFERENCES teaplayer_source (id) ON DELETE RESTRICT,
    CONSTRAINT ck_crawl_task__kind CHECK (task_kind IN
        ('hourly', 'daily', 'category_sync', 'detail_backfill', 'retention', 'reclassify', 'manual')),
    CONSTRAINT ck_crawl_task__status CHECK (status IN
        ('pending', 'running', 'ok', 'partial', 'failed', 'skipped', 'cancelled'))
);
CREATE INDEX ix_crawl_task__due ON teaplayer_crawl_task (status, next_retry_at);
CREATE INDEX ix_crawl_task__source ON teaplayer_crawl_task (source_id, started_at DESC);
COMMENT ON TABLE teaplayer_crawl_task IS '一轮内每源任务与断点';

CREATE TABLE teaplayer_fetch_task (
    id                bigint      GENERATED ALWAYS AS IDENTITY,
    source_id         text        NOT NULL,
    item_id           text        NOT NULL,
    task_kind         text        NOT NULL,
    priority          smallint    NOT NULL DEFAULT 100,
    attempts          smallint    NOT NULL DEFAULT 0,
    next_at           timestamptz NOT NULL DEFAULT now(),
    status            text        NOT NULL DEFAULT 'pending',
    context           jsonb       NOT NULL DEFAULT '{}',
    last_error_code   text,
    last_error_detail text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_fetch_task PRIMARY KEY (id),
    CONSTRAINT ux_fetch_task__source_item_kind UNIQUE (source_id, item_id, task_kind),
    CONSTRAINT fk_fetch_task__source FOREIGN KEY (source_id)
        REFERENCES teaplayer_source (id) ON DELETE RESTRICT,
    CONSTRAINT ck_fetch_task__kind CHECK (task_kind IN ('detail', 'image', 'resolve_play')),
    CONSTRAINT ck_fetch_task__status CHECK (status IN ('pending', 'done', 'dead'))
);
CREATE INDEX ix_fetch_task__due ON teaplayer_fetch_task (status, next_at);
CREATE INDEX ix_fetch_task__dead ON teaplayer_fetch_task (source_id, status) WHERE status = 'dead';
COMMENT ON TABLE teaplayer_fetch_task IS '条目级重试队列；item_id 为自然键，不建外键（失败时条目可能尚未入库）';

-- ---------------------------------------------------------------------------
-- 11-13. 分类器版本、规则、人工纠偏
-- ---------------------------------------------------------------------------
CREATE TABLE teaplayer_classifier_release (
    version           text        NOT NULL,
    merge_key_version text        NOT NULL,
    rules_sha256      text        NOT NULL,
    normalize_profile text        NOT NULL DEFAULT 'zh-v1',
    status            text        NOT NULL,
    effective_from    timestamptz NOT NULL,
    created_by        text,
    note              text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_classifier_release PRIMARY KEY (version),
    CONSTRAINT ck_classifier_release__version CHECK (version ~ '^cat-v[0-9]+$'),
    CONSTRAINT ck_classifier_release__rules_sha CHECK (length(rules_sha256) = 64),
    CONSTRAINT ck_classifier_release__status CHECK (status IN ('draft', 'active', 'retired'))
);
CREATE UNIQUE INDEX ux_classifier_release__active ON teaplayer_classifier_release (status)
    WHERE status = 'active';
COMMENT ON TABLE teaplayer_classifier_release IS '分类器版本注册；全局只允许一个 active';

CREATE TABLE teaplayer_category_rule (
    id                 bigint      GENERATED ALWAYS AS IDENTITY,
    classifier_version text        NOT NULL,
    ordinal            smallint    NOT NULL,
    target_category    text        NOT NULL,
    match_kind         text        NOT NULL,
    pattern            text        NOT NULL,
    exclude_patterns   text[]      NOT NULL DEFAULT '{}',
    applies_to         text        NOT NULL DEFAULT 'name',
    is_terminal        boolean     NOT NULL DEFAULT true,
    note               text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_category_rule PRIMARY KEY (id),
    CONSTRAINT ux_category_rule__version_ordinal UNIQUE (classifier_version, ordinal),
    CONSTRAINT fk_category_rule__version FOREIGN KEY (classifier_version)
        REFERENCES teaplayer_classifier_release (version) ON DELETE RESTRICT,
    CONSTRAINT ck_category_rule__target CHECK (target_category IN
        ('movie', 'tv', 'anime', 'variety', 'documentary', 'short', 'other')),
    CONSTRAINT ck_category_rule__match CHECK (match_kind IN
        ('contains', 'prefix', 'suffix', 'equals', 'regex')),
    CONSTRAINT ck_category_rule__applies CHECK (applies_to IN ('name', 'parent_name')),
    CONSTRAINT ck_category_rule__ordinal CHECK (ordinal >= 1)
);
CREATE INDEX ix_category_rule__version ON teaplayer_category_rule (classifier_version, target_category);
COMMENT ON TABLE teaplayer_category_rule IS '分类规则；发布后不可变，改规则=新建版本';

CREATE TABLE teaplayer_category_override (
    id            bigint      GENERATED ALWAYS AS IDENTITY,
    scope         text        NOT NULL,
    source_id     text,
    type_id       text,
    item_id       bigint,
    merge_key     text,
    category_code text        NOT NULL,
    note          text        NOT NULL,
    created_by    text        NOT NULL,
    updated_by    text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_category_override PRIMARY KEY (id),
    CONSTRAINT fk_category_override__source FOREIGN KEY (source_id)
        REFERENCES teaplayer_source (id) ON DELETE CASCADE,
    CONSTRAINT fk_category_override__item FOREIGN KEY (item_id)
        REFERENCES teaplayer_source_item (id) ON DELETE CASCADE,
    CONSTRAINT ck_category_override__scope CHECK (
        (scope = 'source_type' AND source_id IS NOT NULL AND type_id IS NOT NULL
            AND item_id IS NULL AND merge_key IS NULL)
        OR (scope = 'item' AND item_id IS NOT NULL
            AND source_id IS NULL AND type_id IS NULL AND merge_key IS NULL)
        OR (scope = 'group' AND merge_key IS NOT NULL
            AND source_id IS NULL AND type_id IS NULL AND item_id IS NULL)
    ),
    CONSTRAINT ck_category_override__category CHECK (category_code IN
        ('movie', 'tv', 'anime', 'variety', 'documentary', 'short', 'other'))
);
CREATE UNIQUE INDEX ux_category_override__source_type ON teaplayer_category_override (source_id, type_id)
    WHERE scope = 'source_type';
CREATE UNIQUE INDEX ux_category_override__item ON teaplayer_category_override (item_id)
    WHERE scope = 'item';
CREATE UNIQUE INDEX ux_category_override__group ON teaplayer_category_override (merge_key)
    WHERE scope = 'group';
COMMENT ON TABLE teaplayer_category_override IS '人工纠偏：source_type/item/group 三种粒度，优先级最高';

-- ---------------------------------------------------------------------------
-- 14-16. 配置定义、当前值、变更历史
-- ---------------------------------------------------------------------------
CREATE TABLE teaplayer_config_def (
    config_key      text        NOT NULL,
    group_name      text        NOT NULL,
    value_type      text        NOT NULL,
    default_value   jsonb       NOT NULL,
    constraints     jsonb       NOT NULL DEFAULT '{}',
    scope           text        NOT NULL,
    apply_mode      text        NOT NULL,
    is_secret       boolean     NOT NULL DEFAULT false,
    is_sensitive    boolean     NOT NULL DEFAULT false,
    is_required     boolean     NOT NULL DEFAULT false,
    description     text        NOT NULL,
    owner_module    text        NOT NULL,
    sort_order      smallint    NOT NULL DEFAULT 100,
    deprecated_at   timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_config_def PRIMARY KEY (config_key),
    CONSTRAINT ck_config_def__key CHECK (config_key ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,4}$'),
    CONSTRAINT ck_config_def__group CHECK (group_name IN
        ('retention', 'crawl', 'http', 'search', 'feed', 'image', 'admin', 'logging', 'metrics', 'system')),
    CONSTRAINT ck_config_def__type CHECK (value_type IN
        ('string', 'int', 'long', 'decimal', 'bool', 'duration', 'bytes', 'enum', 'url', 'regex', 'json', 'secret')),
    CONSTRAINT ck_config_def__scope CHECK (scope IN ('bootstrap', 'runtime', 'derived')),
    CONSTRAINT ck_config_def__apply CHECK (apply_mode IN ('hot', 'next_run', 'restart'))
);
CREATE INDEX ix_config_def__group ON teaplayer_config_def (group_name, sort_order);
COMMENT ON TABLE teaplayer_config_def IS '配置定义：类型、默认值、约束与生效模式';

CREATE TABLE teaplayer_config_value (
    config_key   text        NOT NULL,
    value_json   jsonb,
    value_cipher bytea,
    value_nonce  bytea,
    key_id       text,
    value_hint   text,
    version      bigint      NOT NULL DEFAULT 1,
    updated_by   text        NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_config_value PRIMARY KEY (config_key),
    CONSTRAINT fk_config_value__def FOREIGN KEY (config_key)
        REFERENCES teaplayer_config_def (config_key) ON DELETE RESTRICT,
    CONSTRAINT ck_config_value__one_form CHECK ((value_json IS NULL) <> (value_cipher IS NULL)),
    CONSTRAINT ck_config_value__nonce CHECK ((value_cipher IS NULL) = (value_nonce IS NULL)),
    CONSTRAINT ck_config_value__nonce_len CHECK (value_nonce IS NULL OR octet_length(value_nonce) = 12)
);
COMMENT ON TABLE teaplayer_config_value IS '配置当前生效值；secret 为 AES-256-GCM 信封密文';

CREATE TABLE teaplayer_config_revision (
    id               bigint      GENERATED ALWAYS AS IDENTITY,
    config_key       text        NOT NULL,
    revision_no      integer     NOT NULL,
    change_kind      text        NOT NULL,
    old_value_json   jsonb,
    new_value_json   jsonb,
    old_value_cipher bytea,
    new_value_cipher bytea,
    old_hint         text,
    new_hint         text,
    reason           text        NOT NULL,
    actor_type       text        NOT NULL,
    actor_id         text        NOT NULL,
    actor_label      text        NOT NULL,
    request_id       text,
    trace_id         text,
    ip               inet,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_config_revision PRIMARY KEY (id),
    CONSTRAINT ux_config_revision__key_no UNIQUE (config_key, revision_no),
    CONSTRAINT ck_config_revision__kind CHECK (change_kind IN
        ('create', 'update', 'reset', 'rollback', 'import')),
    CONSTRAINT ck_config_revision__reason CHECK (length(reason) <= 200)
);
CREATE INDEX ix_config_revision__created ON teaplayer_config_revision (created_at DESC);
COMMENT ON TABLE teaplayer_config_revision IS '配置变更历史；不可变，含 secret 旧密文以支持回滚';

-- ---------------------------------------------------------------------------
-- 17-19. 管理员、会话、客户端令牌
-- ---------------------------------------------------------------------------
CREATE TABLE teaplayer_admin_user (
    id                   bigint      GENERATED ALWAYS AS IDENTITY,
    username             text        NOT NULL,
    display_name         text,
    password_hash        text        NOT NULL,
    role                 text        NOT NULL,
    status               text        NOT NULL DEFAULT 'active',
    must_change_password boolean     NOT NULL DEFAULT true,
    failed_login_count   smallint    NOT NULL DEFAULT 0,
    locked_until         timestamptz,
    last_login_at        timestamptz,
    last_login_ip        inet,
    password_changed_at  timestamptz,
    created_by           text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    revision             integer     NOT NULL DEFAULT 1,
    CONSTRAINT pk_admin_user PRIMARY KEY (id),
    CONSTRAINT ux_admin_user__username UNIQUE (username),
    CONSTRAINT ck_admin_user__username CHECK (username ~ '^[a-z][a-z0-9_.-]{2,31}$'),
    CONSTRAINT ck_admin_user__hash CHECK (password_hash LIKE '$argon2id$%'),
    CONSTRAINT ck_admin_user__role CHECK (role IN ('owner', 'admin', 'viewer')),
    CONSTRAINT ck_admin_user__status CHECK (status IN ('active', 'disabled', 'locked'))
);
COMMENT ON TABLE teaplayer_admin_user IS '管理员；禁用不删，至少保留一个 owner';

CREATE TABLE teaplayer_admin_session (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id      bigint      NOT NULL,
    token_hash   text        NOT NULL,
    csrf_hash    text        NOT NULL,
    issued_at    timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    revoked_at   timestamptz,
    ip           inet,
    user_agent   text,
    CONSTRAINT pk_admin_session PRIMARY KEY (id),
    CONSTRAINT ux_admin_session__token_hash UNIQUE (token_hash),
    CONSTRAINT fk_admin_session__user FOREIGN KEY (user_id)
        REFERENCES teaplayer_admin_user (id) ON DELETE CASCADE,
    CONSTRAINT ck_admin_session__token_hash CHECK (length(token_hash) = 64),
    CONSTRAINT ck_admin_session__csrf_hash CHECK (length(csrf_hash) = 64)
);
CREATE INDEX ix_admin_session__user ON teaplayer_admin_session (user_id, expires_at);
CREATE INDEX ix_admin_session__expires ON teaplayer_admin_session (expires_at);
COMMENT ON TABLE teaplayer_admin_session IS '管理端会话；库只存 token/csrf 的 sha256';

CREATE TABLE teaplayer_api_token (
    id           bigint      GENERATED ALWAYS AS IDENTITY,
    label        text        NOT NULL,
    token_hash   text        NOT NULL,
    token_prefix varchar(16) NOT NULL,
    scopes       text[]      NOT NULL DEFAULT '{read}',
    status       text        NOT NULL DEFAULT 'active',
    created_by   text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz,
    last_used_at timestamptz,
    last_used_ip inet,
    revoked_at   timestamptz,
    revoked_by   text,
    rotated_from bigint,
    CONSTRAINT pk_api_token PRIMARY KEY (id),
    CONSTRAINT ux_api_token__token_hash UNIQUE (token_hash),
    CONSTRAINT fk_api_token__rotated_from FOREIGN KEY (rotated_from)
        REFERENCES teaplayer_api_token (id) ON DELETE SET NULL,
    CONSTRAINT ck_api_token__hash CHECK (length(token_hash) = 64),
    CONSTRAINT ck_api_token__scopes CHECK (scopes <@ ARRAY['read', 'admin']::text[]
        AND array_length(scopes, 1) >= 1),
    CONSTRAINT ck_api_token__status CHECK (status IN ('active', 'revoked'))
);
CREATE INDEX ix_api_token__active ON teaplayer_api_token (status) WHERE status = 'active';
COMMENT ON TABLE teaplayer_api_token IS '客户端令牌；只存 sha256，明文仅在创建时展示一次';

-- ---------------------------------------------------------------------------
-- 20. 审计（append-only）
-- ---------------------------------------------------------------------------
CREATE TABLE teaplayer_audit_log (
    id           bigint      GENERATED ALWAYS AS IDENTITY,
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    actor_type   text        NOT NULL,
    actor_id     text,
    actor_label  text,
    action       text        NOT NULL,
    target_type  text,
    target_id    text,
    result       text        NOT NULL,
    "before"     jsonb,
    "after"      jsonb,
    changed_keys text[],
    reason       text,
    request_id   text,
    trace_id     text,
    ip           inet,
    user_agent   text,
    error_code   text,
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT ck_audit_log__actor_type CHECK (actor_type IN ('admin', 'token', 'system', 'scheduler')),
    CONSTRAINT ck_audit_log__result CHECK (result IN ('ok', 'denied', 'error')),
    CONSTRAINT ck_audit_log__action CHECK (length(action) <= 64)
);
CREATE INDEX ix_audit_log__occurred ON teaplayer_audit_log (occurred_at DESC);
CREATE INDEX ix_audit_log__target ON teaplayer_audit_log (target_type, target_id, occurred_at DESC);
CREATE INDEX ix_audit_log__actor ON teaplayer_audit_log (actor_type, actor_id, occurred_at DESC);
CREATE INDEX ix_audit_log__action ON teaplayer_audit_log (action, occurred_at DESC);
COMMENT ON TABLE teaplayer_audit_log IS '管理动作审计，append-only；清理任务需显式 teahome.audit_purge=on';

CREATE OR REPLACE FUNCTION teaplayer_audit_log_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('teahome.audit_purge', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'teaplayer_audit_log is append-only';
END;
$$;

CREATE TRIGGER trg_audit_log__immutable
    BEFORE UPDATE OR DELETE ON teaplayer_audit_log
    FOR EACH ROW EXECUTE FUNCTION teaplayer_audit_log_immutable();

-- ---------------------------------------------------------------------------
-- 21. 任务租约
-- ---------------------------------------------------------------------------
CREATE TABLE teaplayer_job_lock (
    lock_key     text        NOT NULL,
    owner_id     text        NOT NULL,
    run_id       bigint,
    acquired_at  timestamptz NOT NULL DEFAULT now(),
    heartbeat_at timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    released_at  timestamptz,
    note         text,
    CONSTRAINT pk_job_lock PRIMARY KEY (lock_key),
    CONSTRAINT ck_job_lock__lease CHECK (expires_at > acquired_at)
);
CREATE INDEX ix_job_lock__expires ON teaplayer_job_lock (expires_at);
COMMENT ON TABLE teaplayer_job_lock IS '可观测任务租约；硬互斥仍由 pg_try_advisory_lock 提供';

-- ---------------------------------------------------------------------------
-- 版本标记
-- ---------------------------------------------------------------------------
INSERT INTO teaplayer_service_meta (meta_key, meta_value)
VALUES ('schema_version', '2')
ON CONFLICT (meta_key) DO UPDATE
    SET meta_value = '2', updated_at = now();
