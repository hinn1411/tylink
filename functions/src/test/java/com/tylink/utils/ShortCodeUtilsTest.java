package com.tylink.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortCodeUtilsTest {

    @Test
    void generateProducesValidShortCodes() {
        for (int i = 0; i < 100; i++) {
            assertTrue(ShortCodeUtils.isValid(ShortCodeUtils.generate()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"aB3xY9Z", "0000000", "zzzzzzz", "ABCDEFG", "1234567"})
    void acceptsSevenCharacterBase62Codes(String shortCode) {
        assertTrue(ShortCodeUtils.isValid(shortCode));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc123", "a", ""})
    void rejectsTooShortCodes(String shortCode) {
        assertFalse(ShortCodeUtils.isValid(shortCode));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc123456", "aB3xY9ZaB3xY9Z"})
    void rejectsTooLongCodes(String shortCode) {
        assertFalse(ShortCodeUtils.isValid(shortCode));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc-123", "abc_123", "abc 123", "abc#123", "abc.123", "é234567"})
    void rejectsCodesWithInvalidCharacters(String shortCode) {
        assertFalse(ShortCodeUtils.isValid(shortCode));
    }

    @Test
    void rejectsBlankAndNullInput() {
        assertFalse(ShortCodeUtils.isValid("   "));
        assertFalse(ShortCodeUtils.isValid(null));
    }
}
