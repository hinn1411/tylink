package com.tylink.features.list.model;

import java.util.List;

public record ListUrlsResponse(List<UrlSummary> items, String nextCursor) {
}
