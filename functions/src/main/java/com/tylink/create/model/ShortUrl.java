package com.tylink.create.model;

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
        item.put("PK", AttributeValue.fromS("URL#" + shortCode));
        item.put("SK", AttributeValue.fromS("METADATA"));
        item.put("longUrl", AttributeValue.fromS(longUrl));
        item.put("visibility", AttributeValue.fromS(visibility.name()));
        item.put("status", AttributeValue.fromS("ACTIVE"));
        item.put("createdAt", AttributeValue.fromS(createdAt));

        // Anonymous PUBLIC creates have no owner: omit ownerId and both GSI1 key
        // attributes together (a composite-key GSI needs both present to index the
        // item at all, so writing only one would just be dead attribute noise).
        if (ownerId != null) {
            String owner = "USER#" + ownerId;
            item.put("ownerId", AttributeValue.fromS(owner));
            item.put("GSI1_PK", AttributeValue.fromS(owner));
            item.put("GSI1_SK", AttributeValue.fromS("URL#" + createdAt + "#" + shortCode));
        }
        return item;
    }
}
