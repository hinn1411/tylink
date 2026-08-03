package com.tylink.repository;

import com.tylink.model.ShortUrl;

public interface UrlRepository {

    /**
     * Persists the given ShortUrl. Implementations must wrap any storage-layer
     * failure as a UrlRepositoryException rather than letting it escape raw.
     */
    void save(ShortUrl shortUrl) throws UrlRepositoryException;

    /**
     * Looks up a ShortUrl by its shortCode. Returns null if no item exists for that
     * code. Implementations must wrap any storage-layer failure as a
     * UrlRepositoryException rather than letting it escape raw.
     */
    ShortUrl findByShortCode(String shortCode) throws UrlRepositoryException;
}
