package com.tylink.features.shorten.utils;

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
    void validate_wellFormedHttpOrHttpsUrl_returnsTrimmedUrl(String url) {
        String result = LongUrlValidator.validate(url);

        assertEquals(url.trim(), result);
    }

    @Test
    void validate_urlWithLeadingAndTrailingWhitespace_returnsTrimmedUrl() {
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
    void validate_nonHttpProtocol_returnsNull(String url) {
        String result = LongUrlValidator.validate(url);

        assertNull(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com/<script>alert(1)</script>",
            "http://example.com/\"onmouseover=\"alert(1)",
            "http://example.com/ path-with-space"
    })
    void validate_urlWithIllegalUriCharacters_returnsNull(String url) {
        String result = LongUrlValidator.validate(url);

        assertNull(result);
    }

    @Test
    void validate_urlWithEmbeddedCrlf_returnsNull() {
        String result = LongUrlValidator.validate("http://example.com/\r\nSet-Cookie: evil=1");

        assertNull(result);
    }

    @Test
    void validate_urlWithNoHost_returnsNull() {
        String result = LongUrlValidator.validate("http:///path");

        assertNull(result);
    }

    @Test
    void validate_overlongUrl_returnsNull() {
        String overlong = "https://example.com/" + "a".repeat(2048);

        String result = LongUrlValidator.validate(overlong);

        assertNull(result);
    }

    @Test
    void validate_emptyInput_returnsNull() {
        String result = LongUrlValidator.validate("");

        assertNull(result);
    }

    @Test
    void validate_blankInput_returnsNull() {
        String result = LongUrlValidator.validate("   ");

        assertNull(result);
    }

    @Test
    void validate_nullInput_returnsNull() {
        String result = LongUrlValidator.validate(null);

        assertNull(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "facebook.com",
            "www.facebook.com",
            "example.com/path",
            "//example.com/path"
    })
    void validate_urlWithNoScheme_returnsNull(String url) {
        String result = LongUrlValidator.validate(url);

        assertNull(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "https://example.com/path"
    })
    void validate_urlWithoutSubdomain_returnsSameUrl(String url) {
        String result = LongUrlValidator.validate(url);

        assertEquals(url, result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://93.184.216.34",
            "http://93.184.216.34/path",
            "http://127.0.0.1:8080/path"
    })
    void validate_ipv4Host_returnsSameUrl(String url) {
        String result = LongUrlValidator.validate(url);

        assertEquals(url, result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://[2001:db8::1]",
            "http://[2001:db8::1]/path",
            "http://[::1]:8080/path"
    })
    void validate_ipv6Host_returnsSameUrl(String url) {
        String result = LongUrlValidator.validate(url);

        assertEquals(url, result);
    }
}
