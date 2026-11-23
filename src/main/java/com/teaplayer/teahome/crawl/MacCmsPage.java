package com.teaplayer.teahome.crawl;

import java.util.List;

/** 一页列表结果。 */
public record MacCmsPage(List<RawItem> items, int page, int pageCount, int total) {
}
