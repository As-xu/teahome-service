CREATE TABLE teaplayer_service_meta (
    id          bigint GENERATED ALWAYS AS IDENTITY,
    meta_key    text        NOT NULL,
    meta_value  text        NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_service_meta PRIMARY KEY (id),
    CONSTRAINT ux_service_meta__meta_key UNIQUE (meta_key)
);

INSERT INTO teaplayer_service_meta (meta_key, meta_value)
VALUES ('schema_version', '1');
