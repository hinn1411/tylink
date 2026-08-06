package com.tylink.models;

import com.tylink.utils.TimestampUtils;

public record ShortUrl(String shortCode, String longUrl, String ownerId, Visibility visibility, UrlStatus status,
                        String createdAt, String updatedAt, String deletedAt) {

    public static ShortUrl create(String shortCode, String longUrl, String ownerId, Visibility visibility) {
        return new ShortUrl(shortCode, longUrl, ownerId, visibility, UrlStatus.ACTIVE, TimestampUtils.now(), null, null);
    }
}
