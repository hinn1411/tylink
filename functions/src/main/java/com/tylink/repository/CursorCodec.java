package com.tylink.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Encodes/decodes a DynamoDB LastEvaluatedKey into an opaque cursor string, so AttributeValue
 * never crosses the UrlRepository interface boundary. Internal to this package — only
 * DynamoDbUrlRepository uses it.
 */
final class CursorCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // What listByOwner's LastEvaluatedKey always contains: the base table's primary key plus
    // GSI1's own key attributes (DynamoDB includes both when paginating a GSI query).
    private static final Set<String> REQUIRED_KEYS = Set.of(
            ShortUrlAttributes.PK, ShortUrlAttributes.SK, ShortUrlAttributes.GSI1_PK, ShortUrlAttributes.GSI1_SK);

    private CursorCodec() {
    }

    static String encode(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        Map<String, String> plain = new HashMap<>();
        lastEvaluatedKey.forEach((key, value) -> plain.put(key, value.s()));
        try {
            byte[] json = MAPPER.writeValueAsBytes(plain);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException e) {
            throw new InvalidCursorException("Failed to encode cursor", e);
        }
    }

    static Map<String, AttributeValue> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        Map<String, String> plain;
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor);
            plain = MAPPER.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (IllegalArgumentException | IOException e) {
            throw new InvalidCursorException("Malformed cursor", e);
        }
        if (plain == null || !plain.keySet().containsAll(REQUIRED_KEYS)) {
            throw new InvalidCursorException("Cursor missing expected key attributes");
        }
        Map<String, AttributeValue> key = new HashMap<>();
        plain.forEach((k, v) -> key.put(k, AttributeValue.fromS(v)));
        return key;
    }
}
