package com.tylink.features.list.util;

import com.tylink.features.list.model.UrlSummary;
import com.tylink.model.ShortUrl;

import java.util.List;

public final class UrlSummaryMapper {

    private UrlSummaryMapper() {
    }

    public static List<UrlSummary> toSummaries(List<ShortUrl> shortUrls) {
        return shortUrls.stream()
                .map(UrlSummaryMapper::toSummary)
                .toList();
    }

    public static UrlSummary toSummary(ShortUrl shortUrl) {
        return new UrlSummary(shortUrl.shortCode(), shortUrl.longUrl(), shortUrl.createdAt());
    }
}
