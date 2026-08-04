package com.tylink.repository;

import com.tylink.model.ShortUrl;

public interface UrlRepository {

    void save(ShortUrl shortUrl) throws UrlRepositoryException;

    ShortUrl findByShortCode(String shortCode) throws UrlRepositoryException;

    /**
     * @param cursor opaque value from a previous UrlPage.nextCursor(), or null for the first page
     * @throws InvalidCursorException if cursor is present but malformed/tampered
     */
    UrlPage listByOwner(String ownerId, int limit, String cursor) throws UrlRepositoryException;
}
