package com.tylink.shorten.repository;

import com.tylink.shorten.model.ShortUrl;
import com.tylink.shorten.model.Visibility;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoDbUrlRepositoryTest {

    private static final String TABLE_NAME = "UrlTable";

    @Test
    void savePutsItemBuiltFromShortUrlToItem() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());
        DynamoDbUrlRepository repository = new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);

        ShortUrl shortUrl = ShortUrl.create("abc1234", "https://example.com/x", "u1", Visibility.PUBLIC);

        repository.save(shortUrl);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        assertEquals(TABLE_NAME, captor.getValue().tableName());
        assertEquals(shortUrl.toItem(), captor.getValue().item());
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
}
