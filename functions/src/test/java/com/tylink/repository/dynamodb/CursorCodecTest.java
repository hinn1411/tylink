package com.tylink.repository.dynamodb;

import com.tylink.repository.pagination.InvalidCursorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CursorCodecTest {

    private static final Map<String, AttributeValue> LAST_EVALUATED_KEY = Map.of(
            ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"),
            ShortUrlAttributes.SK, AttributeValue.fromS("METADATA"),
            ShortUrlAttributes.GSI1_PK, AttributeValue.fromS("USER#u1"),
            ShortUrlAttributes.GSI1_SK, AttributeValue.fromS("URL#2026-01-01T00:00:00.000000000Z#abc1234"));

    @Test
    void encodeAndDecode_validLastEvaluatedKey_roundTripsToOriginalMap() {
        String cursor = CursorCodec.encode(LAST_EVALUATED_KEY);

        Map<String, AttributeValue> decoded = CursorCodec.decode(cursor);

        assertEquals(LAST_EVALUATED_KEY, decoded);
    }

    @Test
    void encode_nullMap_returnsNull() {
        String cursor = CursorCodec.encode(null);

        assertNull(cursor);
    }

    @Test
    void encode_emptyMap_returnsNull() {
        String cursor = CursorCodec.encode(Map.of());

        assertNull(cursor);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"   "})
    void decode_nullOrBlankCursor_returnsNull(String cursor) {
        Map<String, AttributeValue> decoded = CursorCodec.decode(cursor);

        assertNull(decoded);
    }

    @Test
    void decode_nonBase64Cursor_throwsInvalidCursorException() {
        assertThrows(InvalidCursorException.class, () -> CursorCodec.decode("not-valid-base64!!!"));
    }

    @Test
    void decode_base64CursorNotJson_throwsInvalidCursorException() {
        String garbage = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not json".getBytes());

        assertThrows(InvalidCursorException.class, () -> CursorCodec.decode(garbage));
    }

    @Test
    void decode_cursorMissingExpectedKeys_throwsInvalidCursorException() {
        String incomplete = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"foo\":\"bar\"}".getBytes());

        assertThrows(InvalidCursorException.class, () -> CursorCodec.decode(incomplete));
    }
}
