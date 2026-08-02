package com.tylink.features.shorten.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongUrlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/path?foo=bar&baz=qux#section",
            "http://example.com",
            "HTTPS://example.com/mixed-case-scheme",
            "https://example.com/some/very/long/path?id=1"
    })
    void acceptsWellFormedHttpAndHttpsUrls(String url) {
        assertEquals(Optional.of(url.trim()), LongUrlValidator.validate(url));
    }

    @Test
    void acceptsUrlWithLeadingAndTrailingWhitespaceAfterTrimming() {
        Optional<String> result = LongUrlValidator.validate("  https://example.com/x  ");
        assertEquals(Optional.of("https://example.com/x"), result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "ftp://example.com/file",
            "file:///etc/passwd",
            "vbscript:msgbox(1)"
    })
    void rejectsNonHttpProtocols(String url) {
        assertTrue(LongUrlValidator.validate(url).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com/<script>alert(1)</script>",
            "http://example.com/\"onmouseover=\"alert(1)",
            "http://example.com/ path-with-space"
    })
    void rejectsUrlsWithIllegalUriCharacters(String url) {
        assertTrue(LongUrlValidator.validate(url).isEmpty());
    }

    @Test
    void rejectsUrlWithEmbeddedCrlf() {
        assertTrue(LongUrlValidator.validate("http://example.com/\r\nSet-Cookie: evil=1").isEmpty());
    }

    @Test
    void rejectsUrlWithNoHost() {
        assertTrue(LongUrlValidator.validate("http:///path").isEmpty());
    }

    @Test
    void rejectsOverlongUrl() {
        String overlong = "https://example.com/" + "a".repeat(2048);
        assertTrue(LongUrlValidator.validate(overlong).isEmpty());
    }

    @Test
    void rejectsBlankAndNullInput() {
        assertTrue(LongUrlValidator.validate("").isEmpty());
        assertTrue(LongUrlValidator.validate("   ").isEmpty());
        assertTrue(LongUrlValidator.validate(null).isEmpty());
    }
}
