package com.tylink.features.shorten.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LongUrlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/path?foo=bar&baz=qux#section",
            "http://example.com",
            "HTTPS://example.com/mixed-case-scheme",
            "https://example.com/some/very/long/path?id=1"
    })
    void acceptsWellFormedHttpAndHttpsUrls(String url) {
        assertEquals(url.trim(), LongUrlValidator.validate(url));
    }

    @Test
    void acceptsUrlWithLeadingAndTrailingWhitespaceAfterTrimming() {
        String result = LongUrlValidator.validate("  https://example.com/x  ");
        assertEquals("https://example.com/x", result);
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
        assertNull(LongUrlValidator.validate(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com/<script>alert(1)</script>",
            "http://example.com/\"onmouseover=\"alert(1)",
            "http://example.com/ path-with-space"
    })
    void rejectsUrlsWithIllegalUriCharacters(String url) {
        assertNull(LongUrlValidator.validate(url));
    }

    @Test
    void rejectsUrlWithEmbeddedCrlf() {
        assertNull(LongUrlValidator.validate("http://example.com/\r\nSet-Cookie: evil=1"));
    }

    @Test
    void rejectsUrlWithNoHost() {
        assertNull(LongUrlValidator.validate("http:///path"));
    }

    @Test
    void rejectsOverlongUrl() {
        String overlong = "https://example.com/" + "a".repeat(2048);
        assertNull(LongUrlValidator.validate(overlong));
    }

    @Test
    void rejectsBlankAndNullInput() {
        assertNull(LongUrlValidator.validate(""));
        assertNull(LongUrlValidator.validate("   "));
        assertNull(LongUrlValidator.validate(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "facebook.com",
            "www.facebook.com",
            "example.com/path",
            "//example.com/path"
    })
    void rejectsUrlsWithNoScheme(String url) {
        assertNull(LongUrlValidator.validate(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "https://example.com/path"
    })
    void acceptsUrlsWithoutSubdomain(String url) {
        assertEquals(url, LongUrlValidator.validate(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://93.184.216.34",
            "http://93.184.216.34/path",
            "http://127.0.0.1:8080/path"
    })
    void acceptsIpv4Hosts(String url) {
        assertEquals(url, LongUrlValidator.validate(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://[2001:db8::1]",
            "http://[2001:db8::1]/path",
            "http://[::1]:8080/path"
    })
    void acceptsIpv6Hosts(String url) {
        assertEquals(url, LongUrlValidator.validate(url));
    }
}
