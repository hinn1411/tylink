package com.tylink.shorten.model;

import com.tylink.shorten.util.ShortUrlAttributes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public record ShortUrl(String shortCode, String longUrl, String ownerId, Visibility visibility, String createdAt) {

    public static ShortUrl create(String shortCode, String longUrl, String ownerId, Visibility visibility) {
        return new ShortUrl(shortCode, longUrl, ownerId, visibility, Instant.now().toString());
    }

    public Map<String, AttributeValue> toItem() {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ShortUrlAttributes.PK, AttributeValue.fromS(ShortUrlAttributes.URL_KEY_PREFIX + shortCode));
        item.put(ShortUrlAttributes.SK, AttributeValue.fromS(ShortUrlAttributes.SK_METADATA));
        item.put(ShortUrlAttributes.LONG_URL, AttributeValue.fromS(longUrl));
        item.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS(visibility.name()));
        item.put(ShortUrlAttributes.STATUS, AttributeValue.fromS(ShortUrlAttributes.STATUS_ACTIVE));
        item.put(ShortUrlAttributes.CREATED_AT, AttributeValue.fromS(createdAt));

        // Do not partition url for unauthenticated users
        if (ownerId != null) {
            String owner = ShortUrlAttributes.USER_KEY_PREFIX + ownerId;
            item.put(ShortUrlAttributes.OWNER_ID, AttributeValue.fromS(owner));
            item.put(ShortUrlAttributes.GSI1_PK, AttributeValue.fromS(owner));
            item.put(ShortUrlAttributes.GSI1_SK,
                    AttributeValue.fromS(ShortUrlAttributes.URL_KEY_PREFIX + createdAt + "#" + shortCode));
        }
        return item;
    }
}
