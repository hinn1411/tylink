package com.tylink.model;

import java.time.Instant;

public record ShortUrl(String shortCode, String longUrl, String ownerId, Visibility visibility, String createdAt) {

    public static ShortUrl create(String shortCode, String longUrl, String ownerId, Visibility visibility) {
        return new ShortUrl(shortCode, longUrl, ownerId, visibility, Instant.now().toString());
    }
}
