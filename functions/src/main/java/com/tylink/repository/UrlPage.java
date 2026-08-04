package com.tylink.repository;

import com.tylink.model.ShortUrl;

import java.util.List;

public record UrlPage(List<ShortUrl> items, String nextCursor) {
}
