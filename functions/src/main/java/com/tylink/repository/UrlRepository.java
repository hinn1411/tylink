package com.tylink.repository;

import com.tylink.models.ShortUrl;
import com.tylink.repository.pagination.InvalidCursorException;
import com.tylink.repository.pagination.UrlPage;

public interface UrlRepository {

    void save(ShortUrl shortUrl) throws UrlRepositoryException;

    ShortUrl findByShortCode(String shortCode) throws UrlRepositoryException;

    /**
     * @param cursor opaque value from a previous UrlPage.nextCursor(), or null for the first page
     * @throws InvalidCursorException if cursor is present but malformed/tampered
     */
    UrlPage listByOwner(String ownerId, int limit, String cursor) throws UrlRepositoryException;

    /**
     * @return true if the item existed, was owned by ownerId, and was marked DELETED; false if
     * the item doesn't exist or isn't owned by ownerId
     */
    boolean markDeleted(String shortCode, String ownerId) throws UrlRepositoryException;

    /**
     * @return UPDATED with the new state if the item existed, was owned by ownerId, and was
     * ACTIVE; NOT_FOUND if the item doesn't exist or isn't owned by ownerId; ALREADY_DELETED
     * with the current state if the item is owned by ownerId but not ACTIVE
     */
    UpdateOutcome updateLongUrl(String shortCode, String ownerId, String longUrl) throws UrlRepositoryException;
}
