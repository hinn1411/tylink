package com.tylink.features.shorten.models;

import com.tylink.models.Visibility;

public record IdempotentCreateRequest(String idempotencyKey, String longUrl, String ownerId, Visibility visibility) {
}
