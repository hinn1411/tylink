package com.tylink.repository.pagination;

import com.tylink.models.ShortUrl;

import java.util.List;

public record UrlPage(List<ShortUrl> items, String nextCursor) {
}
