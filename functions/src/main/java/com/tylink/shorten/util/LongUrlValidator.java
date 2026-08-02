package com.tylink.shorten.util;

import software.amazon.awssdk.utils.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class LongUrlValidator {

    private static final int MAX_LENGTH = 2048;
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private LongUrlValidator() {
    }

    /**
     * This validator only enforces
     * URL shape (protocol allowlist + strict RFC 3986 syntax), which also
     * rejects raw HTML metacharacters (&lt;, &gt;, ") and control characters
     * (CR/LF/NUL) used in XSS and header-injection payloads.
     */
    public static Optional<String> validate(String rawUrl) {
        if (StringUtils.isBlank(rawUrl)) {
            return Optional.empty();
        }
        String trimmed = rawUrl.trim();
        if (trimmed.length() > MAX_LENGTH || containsControlCharacter(trimmed)) {
            return Optional.empty();
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        if (StringUtils.isBlank(uri.getHost())) {
            return Optional.empty();
        }

        return Optional.of(trimmed);
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
