package com.tylink.repository;

import com.tylink.models.ShortUrl;

public record UpdateOutcome(Status status, ShortUrl shortUrl) {

    public enum Status {
        UPDATED, NOT_FOUND, ALREADY_DELETED
    }

    public static UpdateOutcome updated(ShortUrl shortUrl) {
        return new UpdateOutcome(Status.UPDATED, shortUrl);
    }

    public static UpdateOutcome notFound() {
        return new UpdateOutcome(Status.NOT_FOUND, null);
    }

    public static UpdateOutcome alreadyDeleted(ShortUrl shortUrl) {
        return new UpdateOutcome(Status.ALREADY_DELETED, shortUrl);
    }
}
