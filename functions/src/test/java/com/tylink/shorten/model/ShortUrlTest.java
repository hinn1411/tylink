package com.tylink.shorten.model;

import com.tylink.shorten.util.ShortUrlAttributes;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortUrlTest {

    @Test
    void toItemSetsCorePkSkAndStatusFields() {
        ShortUrl shortUrl = ShortUrl.create("abc1234", "https://example.com/x", null, Visibility.PUBLIC);

        Map<String, AttributeValue> item = shortUrl.toItem();

        assertEquals("URL#abc1234", item.get(ShortUrlAttributes.PK).s());
        assertEquals("METADATA", item.get(ShortUrlAttributes.SK).s());
        assertEquals("ACTIVE", item.get(ShortUrlAttributes.STATUS).s());
        assertEquals("https://example.com/x", item.get(ShortUrlAttributes.LONG_URL).s());
        assertTrue(item.containsKey(ShortUrlAttributes.CREATED_AT));
        assertFalse(item.get(ShortUrlAttributes.CREATED_AT).s().isBlank());
    }

    @Test
    void toItemReflectsVisibility() {
        ShortUrl publicUrl = ShortUrl.create("abc1234", "https://example.com/x", null, Visibility.PUBLIC);
        ShortUrl privateUrl = ShortUrl.create("def5678", "https://example.com/y", null, Visibility.PRIVATE);

        assertEquals("PUBLIC", publicUrl.toItem().get(ShortUrlAttributes.VISIBILITY).s());
        assertEquals("PRIVATE", privateUrl.toItem().get(ShortUrlAttributes.VISIBILITY).s());
    }

    @Test
    void toItemOmitsOwnerAndGsi1KeysWhenOwnerIdIsNull() {
        ShortUrl shortUrl = ShortUrl.create("abc1234", "https://example.com/x", null, Visibility.PUBLIC);

        Map<String, AttributeValue> item = shortUrl.toItem();

        assertFalse(item.containsKey(ShortUrlAttributes.OWNER_ID));
        assertFalse(item.containsKey(ShortUrlAttributes.GSI1_PK));
        assertFalse(item.containsKey(ShortUrlAttributes.GSI1_SK));
    }

    @Test
    void toItemIncludesOwnerAndGsi1KeysWhenOwnerIdPresent() {
        ShortUrl shortUrl = ShortUrl.create("abc1234", "https://example.com/x", "u1", Visibility.PRIVATE);

        Map<String, AttributeValue> item = shortUrl.toItem();

        assertEquals("USER#u1", item.get(ShortUrlAttributes.OWNER_ID).s());
        assertEquals("USER#u1", item.get(ShortUrlAttributes.GSI1_PK).s());
        assertEquals("URL#" + shortUrl.createdAt() + "#abc1234", item.get(ShortUrlAttributes.GSI1_SK).s());
    }
}
