package com.tylink.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
