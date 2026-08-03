package com.tylink.repository;

import com.tylink.model.ShortUrl;

public interface UrlRepository {

    void save(ShortUrl shortUrl) throws UrlRepositoryException;

    ShortUrl findByShortCode(String shortCode) throws UrlRepositoryException;
}
