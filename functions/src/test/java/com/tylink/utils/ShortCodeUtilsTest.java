package com.tylink.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortCodeUtilsTest {

    @Test
    void generate_calledRepeatedly_alwaysProducesValidShortCodes() {
        for (int i = 0; i < 100; i++) {
            String shortCode = ShortCodeUtils.generate();
            boolean valid = ShortCodeUtils.isValid(shortCode);

            assertTrue(valid);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"aB3xY9Z", "0000000", "zzzzzzz", "ABCDEFG", "1234567"})
    void isValid_sevenCharacterBase62Code_returnsTrue(String shortCode) {
        boolean valid = ShortCodeUtils.isValid(shortCode);

        assertTrue(valid);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc123", "a", ""})
    void isValid_tooShortCode_returnsFalse(String shortCode) {
        boolean valid = ShortCodeUtils.isValid(shortCode);

        assertFalse(valid);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc123456", "aB3xY9ZaB3xY9Z"})
    void isValid_tooLongCode_returnsFalse(String shortCode) {
        boolean valid = ShortCodeUtils.isValid(shortCode);

        assertFalse(valid);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc-123", "abc_123", "abc 123", "abc#123", "abc.123", "é234567"})
    void isValid_codeWithInvalidCharacters_returnsFalse(String shortCode) {
        boolean valid = ShortCodeUtils.isValid(shortCode);

        assertFalse(valid);
    }

    @Test
    void isValid_blankInput_returnsFalse() {
        boolean valid = ShortCodeUtils.isValid("   ");

        assertFalse(valid);
    }

    @Test
    void isValid_nullInput_returnsFalse() {
        boolean valid = ShortCodeUtils.isValid(null);

        assertFalse(valid);
    }
}
