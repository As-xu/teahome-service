-- V3: P1 种子（7 个源、分类器 cat-v1 与规则）
-- 源规格与客户端 teaplayer_flutter/lib/core/data/seed_specs.dart 对齐；
-- 分类规则按客户端 category_map.dart 的语义表达为 regex + exclude。

INSERT INTO teaplayer_source
    (id, name, kind, base_url, spec, allow_hosts, priority, is_enabled, updated_by)
VALUES
    ('hongniu', '红牛资源', 'maccms',
     'https://www.hongniuzy2.com/api.php/provide/vod/',
     $spec$
{
       "id":"hongniu","name":"红牛资源","version":"1.0.0",
       "baseUrl":"https://www.hongniuzy2.com/api.php/provide/vod/",
       "endpoints":{"list":"?ac=list&pg={page}&t={typeId}","search":"?ac=list&wd={keyword}&pg={page}","detail":"?ac=detail&ids={ids}"},
       "itemsPath":"list",
       "fields":{"id":"vod_id","title":"vod_name","year":"vod_year","category":"vod_class|type_name","cover":"vod_pic","remark":"vod_remarks","playFrom":"vod_play_from","playUrl":"vod_play_url"},
       "playUrl":{"groupSep":"$$$","episodeSep":"#","nameSep":"$"}
     }$spec$::jsonb,
     ARRAY['www.hongniuzy2.com'], 10, true, 'seed'),

    ('ffzy', '非凡资源', 'maccms',
     'https://api.ffzyapi.com/api.php/provide/vod/',
     $spec$
{
       "id":"ffzy","name":"非凡资源","version":"1.0.0",
       "baseUrl":"https://api.ffzyapi.com/api.php/provide/vod/",
       "endpoints":{"list":"?ac=list&pg={page}&t={typeId}","search":"?ac=list&wd={keyword}&pg={page}","detail":"?ac=detail&ids={ids}"},
       "itemsPath":"list",
       "fields":{"id":"vod_id","title":"vod_name","year":"vod_year","category":"vod_class|type_name","cover":"vod_pic","remark":"vod_remarks","playFrom":"vod_play_from","playUrl":"vod_play_url"},
       "playUrl":{"groupSep":"$$$","episodeSep":"#","nameSep":"$"}
     }$spec$::jsonb,
     ARRAY['api.ffzyapi.com'], 20, true, 'seed'),

    ('xgzy', '西瓜资源', 'maccms',
     'https://caiji.xgzyapi.com/api.php/provide/vod/',
     $spec$
{
       "id":"xgzy","name":"西瓜资源","version":"1.0.0",
       "baseUrl":"https://caiji.xgzyapi.com/api.php/provide/vod/",
       "endpoints":{"list":"?ac=list&pg={page}&t={typeId}","search":"?ac=list&wd={keyword}&pg={page}","detail":"?ac=detail&ids={ids}"},
       "itemsPath":"list",
       "fields":{"id":"vod_id","title":"vod_name","year":"vod_year","category":"vod_class|type_name","cover":"vod_pic","remark":"vod_remarks","playFrom":"vod_play_from","playUrl":"vod_play_url"},
       "playUrl":{"groupSep":"$$$","episodeSep":"#","nameSep":"$"}
     }$spec$::jsonb,
     ARRAY['caiji.xgzyapi.com'], 30, true, 'seed'),

    ('zuid', '最大资源', 'maccms',
     'https://api.zuidapi.com/api.php/provide/vod/',
     $spec$
{
       "id":"zuid","name":"最大资源","version":"1.0.0",
       "baseUrl":"https://api.zuidapi.com/api.php/provide/vod/",
       "endpoints":{"list":"?ac=list&pg={page}&t={typeId}","search":"?ac=list&wd={keyword}&pg={page}","detail":"?ac=detail&ids={ids}"},
       "itemsPath":"list",
       "fields":{"id":"vod_id","title":"vod_name","year":"vod_year","category":"vod_class|type_name","cover":"vod_pic","remark":"vod_remarks","playFrom":"vod_play_from","playUrl":"vod_play_url"},
       "playUrl":{"groupSep":"$$$","episodeSep":"#","nameSep":"$"}
     }$spec$::jsonb,
     ARRAY['api.zuidapi.com'], 40, true, 'seed'),

    ('jizy', '极速资源', 'maccms',
     'https://jyzyapi.com/api.php/provide/vod/',
     $spec$
{
       "id":"jizy","name":"极速资源","version":"1.0.0",
       "baseUrl":"https://jyzyapi.com/api.php/provide/vod/",
       "endpoints":{"list":"?ac=list&pg={page}&t={typeId}","search":"?ac=list&wd={keyword}&pg={page}","detail":"?ac=detail&ids={ids}"},
       "itemsPath":"list",
       "fields":{"id":"vod_id","title":"vod_name","year":"vod_year","category":"vod_class|type_name","cover":"vod_pic","remark":"vod_remarks","playFrom":"vod_play_from","playUrl":"vod_play_url"},
       "playUrl":{"groupSep":"$$$","episodeSep":"#","nameSep":"$"}
     }$spec$::jsonb,
     ARRAY['jyzyapi.com'], 50, true, 'seed'),

    ('uku', 'U酷资源', 'maccms',
     'https://api.ukuapi88.com/api.php/provide/vod/',
     $spec$
{
       "id":"uku","name":"U酷资源","version":"1.0.0",
       "baseUrl":"https://api.ukuapi88.com/api.php/provide/vod/",
       "endpoints":{"list":"?ac=list&pg={page}&t={typeId}","search":"?ac=list&wd={keyword}&pg={page}","detail":"?ac=detail&ids={ids}"},
       "itemsPath":"list",
       "fields":{"id":"vod_id","title":"vod_name","year":"vod_year","category":"vod_class|type_name","cover":"vod_pic","remark":"vod_remarks","playFrom":"vod_play_from","playUrl":"vod_play_url"},
       "playUrl":{"groupSep":"$$$","episodeSep":"#","nameSep":"$"}
     }$spec$::jsonb,
     ARRAY['api.ukuapi88.com'], 60, true, 'seed'),

    -- 稀饭是 PostgREST，列表/详情/播放签发形状都不同；P1 先登记、停用，
    -- 适配器放到 P1.1，避免用一个半成品影响 MacCMS 六源的验收。
    ('xifan', '稀饭动漫', 'postgrest',
     'https://api.xifanacg.com/rest/v1/',
     $spec$
{
       "id":"xifan","name":"稀饭动漫","version":"1.0.0",
       "baseUrl":"https://api.xifanacg.com/rest/v1/",
       "endpoints":{
         "list":"animes?select=id,title,year:release_year,category:region,cover:cover_url,remark:remarks&is_published=eq.true&order=updated_at.desc&limit=24&offset={offset}",
         "search":"animes?select=id,title,year:release_year,category:region,cover:cover_url,remark:remarks&is_published=eq.true&title=ilike.*{keyword}*&order=updated_at.desc&limit=24&offset={offset}",
         "detail":"animes?select=id,title,year:release_year,category:region,cover:cover_url,remark:remarks,episodes(id,episode_number,title)&id=eq.{ids}"
       },
       "itemsPath":"","pageSize":24,
       "fields":{"id":"id","title":"title","year":"year","category":"category","cover":"cover","remark":"remark"},
       "headers":{"apikey":"sb_publishable_OBIVAWACIX6lPXrO98_z24_HcsmalkA","Authorization":"Bearer sb_publishable_OBIVAWACIX6lPXrO98_z24_HcsmalkA"},
       "play":{"endpoint":"https://api.xifanacg.com/functions/v1/issue-web-playback","method":"POST","body":{"action":"fallback","episode_id":"{episodeId}","source_id":"{sourceId}"},"sources":[2,1,4],"urlPath":"url","label":"稀饭动漫"}
     }$spec$::jsonb,
     ARRAY['api.xifanacg.com'], 900, false, 'seed')
ON CONFLICT (id) DO NOTHING;

INSERT INTO teaplayer_classifier_release
    (version, merge_key_version, rules_sha256, normalize_profile, status, effective_from, created_by, note)
VALUES
    ('cat-v1', 'title-year-v1',
     '87cd8b9d2430fc0d91a731df972535fa7afd3cac4c66f1c111dca932870f20e5',
     'zh-v1', 'active', now(), 'seed',
     '复刻客户端 category_map.dart：纪录片/动漫/综艺/短剧/电视剧/电影，多标签 + 主类')
ON CONFLICT (version) DO NOTHING;

INSERT INTO teaplayer_category_rule
    (classifier_version, ordinal, target_category, match_kind, pattern, exclude_patterns, applies_to, is_terminal, note)
VALUES
    ('cat-v1', 10, 'documentary', 'regex', '纪录|记录', '{}', 'name', true,
     '纪录片/记录片；先于电影，避免「片」误吞'),
    ('cat-v1', 20, 'anime', 'regex', '动漫|动画', '{}', 'name', true,
     '动漫/动画，含动漫电影、动画片'),
    ('cat-v1', 30, 'variety', 'contains', '综艺', '{}', 'name', true,
     '综艺'),
    ('cat-v1', 40, 'short', 'contains', '短剧', '{}', 'name', true,
     '短剧；先于电视剧，避免「剧」后缀误吞'),
    ('cat-v1', 50, 'tv', 'regex', '连续剧|剧$', '{短剧}', 'name', true,
     '连续剧或 xx 剧；排除短剧'),
    ('cat-v1', 60, 'movie', 'regex', '^(电影|电影片)$|片$',
     '{纪录,记录,动漫,动画,短剧,预告,资讯,解说}', 'name', true,
     '电影/电影片/xx 片；排除纪录片、动画片、短剧、预告片、资讯片、解说片')
ON CONFLICT (classifier_version, ordinal) DO NOTHING;

UPDATE teaplayer_service_meta
   SET meta_value = '3', updated_at = now()
 WHERE meta_key = 'schema_version';
