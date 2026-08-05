package com.tylink.features.list.models;

import java.util.List;

public record ListUrlsResponse(List<UrlSummary> items, String nextCursor) {
}
