package com.tylink.features.list.utils;

import com.tylink.features.list.models.UrlSummary;
import com.tylink.models.ShortUrl;

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
