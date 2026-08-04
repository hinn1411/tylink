package com.tylink.features.list.util;

import com.tylink.features.list.model.UrlSummary;
import com.tylink.model.ShortUrl;
import com.tylink.model.UrlStatus;
import com.tylink.model.Visibility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlSummaryMapperTest {

    private static ShortUrl shortUrl(String shortCode) {
        return new ShortUrl(shortCode, "https://example.com/" + shortCode, "u1", Visibility.PRIVATE,
                UrlStatus.ACTIVE, "2026-01-01T00:00:00.000000000Z");
    }

    @Test
    void toSummary_shortUrl_copiesShortCodeLongUrlAndCreatedAt() {
        ShortUrl shortUrl = shortUrl("abc1234");

        UrlSummary summary = UrlSummaryMapper.toSummary(shortUrl);

        assertEquals(shortUrl.shortCode(), summary.shortCode());
        assertEquals(shortUrl.longUrl(), summary.longUrl());
        assertEquals(shortUrl.createdAt(), summary.createdAt());
    }

    @Test
    void toSummaries_multipleShortUrls_mapsEachItemInOrder() {
        List<UrlSummary> summaries = UrlSummaryMapper.toSummaries(List.of(shortUrl("abc1234"), shortUrl("def5678")));

        assertEquals(2, summaries.size());
        assertEquals("abc1234", summaries.get(0).shortCode());
        assertEquals("def5678", summaries.get(1).shortCode());
    }

    @Test
    void toSummaries_emptyList_returnsEmptyList() {
        List<UrlSummary> summaries = UrlSummaryMapper.toSummaries(List.of());

        assertTrue(summaries.isEmpty());
    }
}
