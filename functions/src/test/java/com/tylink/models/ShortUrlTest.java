package com.tylink.models;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortUrlTest {

    @Test
    void createSetsAllFieldsAndTimestamp() {
        ShortUrl shortUrl = ShortUrl.create("abc1234", "https://example.com/x", "u1", Visibility.PRIVATE);

        assertEquals("abc1234", shortUrl.shortCode());
        assertEquals("https://example.com/x", shortUrl.longUrl());
        assertEquals("u1", shortUrl.ownerId());
        assertEquals(Visibility.PRIVATE, shortUrl.visibility());
        assertEquals(UrlStatus.ACTIVE, shortUrl.status());
        assertFalse(shortUrl.createdAt().isBlank());
    }

    @Test
    void createdAtIsFixedWidthNanosecondPrecisionAndParseable() {
        ShortUrl shortUrl = ShortUrl.create("abc1234", "https://example.com/x", "u1", Visibility.PRIVATE);

        assertTrue(shortUrl.createdAt().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{9}Z"));
        assertDoesNotThrow(() -> Instant.parse(shortUrl.createdAt()));
    }
}
