-- P1.1: stable feed ordering, line change detection and query indexes.
-- Group rows are never deleted from now on; empty groups keep their id so
-- cursors, cover caches and detail bookmarks stay valid.

ALTER TABLE teaplayer_source_item ADD COLUMN play_hash char(64);

ALTER TABLE teaplayer_title_group ADD COLUMN feed_seq bigint;
CREATE SEQUENCE teaplayer_title_group_feed_seq;
UPDATE teaplayer_title_group SET feed_seq = id WHERE feed_seq IS NULL;
ALTER TABLE teaplayer_title_group ALTER COLUMN feed_seq SET DEFAULT nextval('teaplayer_title_group_feed_seq');
ALTER TABLE teaplayer_title_group ALTER COLUMN feed_seq SET NOT NULL;
SELECT setval('teaplayer_title_group_feed_seq',
              (SELECT COALESCE(max(feed_seq), 0) + 1 FROM teaplayer_title_group), false);

CREATE INDEX ix_title_group__feed_seq ON teaplayer_title_group (feed_seq DESC, id DESC);
CREATE INDEX ix_title_group__category_feed ON teaplayer_title_group (primary_category, feed_seq DESC, id DESC);
CREATE INDEX ix_title_group__category_codes ON teaplayer_title_group USING gin (category_codes);
