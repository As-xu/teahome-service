-- 封面缓存键：与 Java 端 sha256(cover_url) 保持一致。
UPDATE teaplayer_source_item
   SET cover_key = encode(sha256(convert_to(cover_url, 'UTF8')), 'hex')
 WHERE cover_url IS NOT NULL
   AND cover_key IS NULL;

UPDATE teaplayer_title_group g
   SET cover_url = i.cover_url,
       cover_key = i.cover_key
  FROM teaplayer_source_item i
 WHERE i.id = g.primary_item_id
   AND g.cover_key IS NULL;
