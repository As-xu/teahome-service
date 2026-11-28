-- P7 §5.1 方案 A：手机「在电视上搜」的推送通道。
-- 单槽语义——TV 端只取最新一条，不读历史；写入时顺手清掉 1 小时前的旧行，
-- 避免每次推送都留一行。授权由 deploy/postgres/init/01-roles.sh 的
-- ALTER DEFAULT PRIVILEGES 自动覆盖（teahome_migrate 建表，teahome_app 可读写）。
CREATE TABLE teaplayer_tv_search_topic (
    id          bigserial   NOT NULL,
    topic       text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_tv_search_topic PRIMARY KEY (id),
    -- DB 侧兜底：应用层已按去空白后的长度校验，这里只防绕过接口的写入。
    CONSTRAINT ck_tv_search_topic__topic CHECK (char_length(topic) BETWEEN 1 AND 40)
);

CREATE INDEX ix_tv_search_topic__created ON teaplayer_tv_search_topic (created_at DESC);

COMMENT ON TABLE teaplayer_tv_search_topic IS '手机推送到 TV 的搜索关键词，单槽（只读最新一条）';

UPDATE teaplayer_service_meta
   SET meta_value = '12', updated_at = now()
 WHERE meta_key = 'schema_version';
