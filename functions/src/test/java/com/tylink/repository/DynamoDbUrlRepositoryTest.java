package com.tylink.repository;

import com.tylink.model.ShortUrl;
import com.tylink.model.Visibility;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoDbUrlRepositoryTest {

    private static final String TABLE_NAME = "UrlTable";

    @Test
    void savePutsItemIntoTheConfiguredTable() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());
        DynamoDbUrlRepository repository = new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);

        repository.save(ShortUrl.create("abc1234", "https://example.com/x", null, Visibility.PUBLIC));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        assertEquals(TABLE_NAME, captor.getValue().tableName());
    }

    @Test
    void savePutsCorePkSkAndStatusFields() {
        Map<String, AttributeValue> item = save(ShortUrl.create("abc1234", "https://example.com/x", null, Visibility.PUBLIC));

        assertEquals("URL#abc1234", item.get(ShortUrlAttributes.PK).s());
        assertEquals("METADATA", item.get(ShortUrlAttributes.SK).s());
        assertEquals("ACTIVE", item.get(ShortUrlAttributes.STATUS).s());
        assertEquals("https://example.com/x", item.get(ShortUrlAttributes.LONG_URL).s());
        assertTrue(item.containsKey(ShortUrlAttributes.CREATED_AT));
        assertFalse(item.get(ShortUrlAttributes.CREATED_AT).s().isBlank());
    }

    @Test
    void saveReflectsVisibility() {
        Map<String, AttributeValue> publicItem =
                save(ShortUrl.create("abc1234", "https://example.com/x", null, Visibility.PUBLIC));
        Map<String, AttributeValue> privateItem =
                save(ShortUrl.create("def5678", "https://example.com/y", null, Visibility.PRIVATE));

        assertEquals("PUBLIC", publicItem.get(ShortUrlAttributes.VISIBILITY).s());
        assertEquals("PRIVATE", privateItem.get(ShortUrlAttributes.VISIBILITY).s());
    }

    @Test
    void saveOmitsOwnerAndGsi1KeysWhenOwnerIdIsNull() {
        Map<String, AttributeValue> item = save(ShortUrl.create("abc1234", "https://example.com/x", null, Visibility.PUBLIC));

        assertFalse(item.containsKey(ShortUrlAttributes.OWNER_ID));
        assertFalse(item.containsKey(ShortUrlAttributes.GSI1_PK));
        assertFalse(item.containsKey(ShortUrlAttributes.GSI1_SK));
    }

    @Test
    void saveIncludesOwnerAndGsi1KeysWhenOwnerIdPresent() {
        ShortUrl shortUrl = ShortUrl.create("abc1234", "https://example.com/x", "u1", Visibility.PRIVATE);

        Map<String, AttributeValue> item = save(shortUrl);

        assertEquals("USER#u1", item.get(ShortUrlAttributes.OWNER_ID).s());
        assertEquals("USER#u1", item.get(ShortUrlAttributes.GSI1_PK).s());
        assertEquals("URL#" + shortUrl.createdAt() + "#abc1234", item.get(ShortUrlAttributes.GSI1_SK).s());
    }

    @Test
    void saveWrapsSdkExceptionAsUrlRepositoryException() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        RuntimeException cause = DynamoDbException.builder().message("service unavailable").build();
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenThrow(cause);
        DynamoDbUrlRepository repository = new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);

        ShortUrl shortUrl = ShortUrl.create("abc1234", "https://example.com/x", null, Visibility.PUBLIC);

        UrlRepositoryException thrown = assertThrows(UrlRepositoryException.class, () -> repository.save(shortUrl));
        assertSame(cause, thrown.getCause());
    }

    private Map<String, AttributeValue> save(ShortUrl shortUrl) {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());
        DynamoDbUrlRepository repository = new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);

        repository.save(shortUrl);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        return captor.getValue().item();
    }
}
