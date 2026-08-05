package com.tylink.features.shorten.utils;

import software.amazon.awssdk.utils.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

public final class LongUrlValidator {

    private static final int MAX_LENGTH = 2048;
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private LongUrlValidator() {
    }

    /**
     * Returns the trimmed URL if it is a valid http/https URL, or null otherwise.
     * This validator only enforces
     * URL shape (protocol allowlist + strict RFC 3986 syntax), which also
     * rejects raw HTML metacharacters (&lt;, &gt;, ") and control characters
     * (CR/LF/NUL) used in XSS and header-injection payloads.
     */
    public static String validate(String rawUrl) {
        if (StringUtils.isBlank(rawUrl)) {
            return null;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.length() > MAX_LENGTH) {
            return null;
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return null;
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (StringUtils.isBlank(uri.getHost())) {
            return null;
        }

        return trimmed;
    }
}
