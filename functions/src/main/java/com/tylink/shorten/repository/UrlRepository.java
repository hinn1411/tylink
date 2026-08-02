package com.tylink.shorten.repository;

import com.tylink.shorten.model.ShortUrl;

public interface UrlRepository {

    /**
     * Persists the given ShortUrl. Implementations must wrap any storage-layer
     * failure as a UrlRepositoryException rather than letting it escape raw.
     */
    void save(ShortUrl shortUrl) throws UrlRepositoryException;
}
