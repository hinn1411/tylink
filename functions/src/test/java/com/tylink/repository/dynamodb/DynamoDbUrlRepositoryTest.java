package com.tylink.repository.dynamodb;

import com.tylink.models.ShortUrl;
import com.tylink.models.UrlStatus;
import com.tylink.models.Visibility;
import com.tylink.repository.UrlRepositoryException;
import com.tylink.repository.pagination.InvalidCursorException;
import com.tylink.repository.pagination.UrlPage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Test
    void findByShortCodeUsesCorrectKey() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
        DynamoDbUrlRepository repository = new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);

        repository.findByShortCode("abc1234");

        ArgumentCaptor<GetItemRequest> captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamoDb).getItem(captor.capture());
        assertEquals(TABLE_NAME, captor.getValue().tableName());
        assertEquals("URL#abc1234", captor.getValue().key().get(ShortUrlAttributes.PK).s());
        assertEquals("METADATA", captor.getValue().key().get(ShortUrlAttributes.SK).s());
    }

    @Test
    void findByShortCodeReturnsNullWhenItemDoesNotExist() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
        DynamoDbUrlRepository repository = new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);

        assertNull(repository.findByShortCode("abc1234"));
    }

    @Test
    void findByShortCodeMapsItemBackToShortUrlIncludingOwnerPrefixStrip() {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"));
        item.put(ShortUrlAttributes.LONG_URL, AttributeValue.fromS("https://example.com/x"));
        item.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS("PRIVATE"));
        item.put(ShortUrlAttributes.STATUS, AttributeValue.fromS("ACTIVE"));
        item.put(ShortUrlAttributes.CREATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));
        item.put(ShortUrlAttributes.OWNER_ID, AttributeValue.fromS("USER#u1"));

        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());
        DynamoDbUrlRepository repository = new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);

        ShortUrl shortUrl = repository.findByShortCode("abc1234");

        assertEquals("abc1234", shortUrl.shortCode());
        assertEquals("https://example.com/x", shortUrl.longUrl());
        assertEquals("u1", shortUrl.ownerId());
        assertEquals(Visibility.PRIVATE, shortUrl.visibility());
        assertEquals(UrlStatus.ACTIVE, shortUrl.status());
        assertEquals("2026-01-01T00:00:00Z", shortUrl.createdAt());
    }

    @Test
    void findByShortCodeReturnsNullOwnerIdWhenAbsent() {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"));
        item.put(ShortUrlAttributes.LONG_URL, AttributeValue.fromS("https://example.com/x"));
        item.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS("PUBLIC"));
        item.put(ShortUrlAttributes.STATUS, AttributeValue.fromS("ACTIVE"));
        item.put(ShortUrlAttributes.CREATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));

        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());
        DynamoDbUrlRepository repository = new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);

        assertNull(repository.findByShortCode("abc1234").ownerId());
    }

    @Test
    void findByShortCodeParsesDeletedStatus() {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"));
        item.put(ShortUrlAttributes.LONG_URL, AttributeValue.fromS("https://example.com/x"));
        item.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS("PUBLIC"));
        item.put(ShortUrlAttributes.STATUS, AttributeValue.fromS("DELETED"));
        item.put(ShortUrlAttributes.CREATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));

        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());
        DynamoDbUrlRepository repository = new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);

        assertEquals(UrlStatus.DELETED, repository.findByShortCode("abc1234").status());
    }

    @Test
    void findByShortCode_itemMissingRequiredAttribute_throwsUrlRepositoryExceptionNamingIt() {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"));
        item.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS("PUBLIC"));
        item.put(ShortUrlAttributes.STATUS, AttributeValue.fromS("ACTIVE"));
        item.put(ShortUrlAttributes.CREATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));
        // LONG_URL intentionally omitted to simulate a corrupted/partially written item
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());
        DynamoDbUrlRepository repository = new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);

        UrlRepositoryException thrown =
                assertThrows(UrlRepositoryException.class, () -> repository.findByShortCode("abc1234"));

        assertTrue(thrown.getMessage().contains(ShortUrlAttributes.LONG_URL));
    }

    @Test
    void findByShortCodeWrapsSdkExceptionAsUrlRepositoryException() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        RuntimeException cause = DynamoDbException.builder().message("service unavailable").build();
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenThrow(cause);
        DynamoDbUrlRepository repository = new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);

        UrlRepositoryException thrown =
                assertThrows(UrlRepositoryException.class, () -> repository.findByShortCode("abc1234"));
        assertSame(cause, thrown.getCause());
    }

    @Test
    void markDeleted_itemExistsAndOwnedByCaller_sendsConditionalUpdateWithDeletedStatusAndOwnerCondition() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.updateItem(any(UpdateItemRequest.class))).thenReturn(UpdateItemResponse.builder().build());
        DynamoDbUrlRepository repository = new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);

        boolean deleted = repository.markDeleted("abc1234", "u1");

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDb).updateItem(captor.capture());
        UpdateItemRequest request = captor.getValue();
        assertTrue(deleted);
        assertEquals(TABLE_NAME, request.tableName());
        assertEquals("URL#abc1234", request.key().get(ShortUrlAttributes.PK).s());
        assertEquals("METADATA", request.key().get(ShortUrlAttributes.SK).s());
        assertEquals("SET #status = :deleted", request.updateExpression());
        assertEquals("attribute_exists(PK) AND #ownerId = :ownerId", request.conditionExpression());
        assertEquals(ShortUrlAttributes.STATUS, request.expressionAttributeNames().get("#status"));
        assertEquals(ShortUrlAttributes.OWNER_ID, request.expressionAttributeNames().get("#ownerId"));
        assertEquals("DELETED", request.expressionAttributeValues().get(":deleted").s());
        assertEquals("USER#u1", request.expressionAttributeValues().get(":ownerId").s());
    }

    @Test
    void markDeleted_conditionalCheckFails_returnsFalseWithoutThrowing() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("condition failed").build());
        DynamoDbUrlRepository repository = new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);

        boolean deleted = repository.markDeleted("abc1234", "u1");

        assertFalse(deleted);
    }

    @Test
    void markDeleted_dynamoDbThrowsOtherSdkException_wrapsAsUrlRepositoryException() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        RuntimeException cause = DynamoDbException.builder().message("service unavailable").build();
        when(dynamoDb.updateItem(any(UpdateItemRequest.class))).thenThrow(cause);
        DynamoDbUrlRepository repository = new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);

        UrlRepositoryException thrown =
                assertThrows(UrlRepositoryException.class, () -> repository.markDeleted("abc1234", "u1"));
        assertSame(cause, thrown.getCause());
    }

    private static DynamoDbUrlRepository repository(DynamoDbClient dynamoDb) {
        return new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);
    }

    private static DynamoDbUrlRepository repositoryReturningEmptyPage(DynamoDbClient dynamoDb) {
        when(dynamoDb.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of()).build());
        return repository(dynamoDb);
    }

    private static QueryRequest capturedQueryRequest(DynamoDbClient dynamoDb) {
        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb).query(captor.capture());
        return captor.getValue();
    }

    @Test
    void listByOwner_ownerId_queriesGsi1WithAliasedOwnerPrefixedKeyCondition() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        DynamoDbUrlRepository repository = repositoryReturningEmptyPage(dynamoDb);

        repository.listByOwner("u1", 20, null);

        QueryRequest request = capturedQueryRequest(dynamoDb);
        assertEquals(TABLE_NAME, request.tableName());
        assertEquals(ShortUrlAttributes.GSI1_INDEX_NAME, request.indexName());
        assertEquals("#gsi1_pk = :ownerId", request.keyConditionExpression());
        assertEquals(ShortUrlAttributes.GSI1_PK, request.expressionAttributeNames().get("#gsi1_pk"));
        assertEquals("USER#u1", request.expressionAttributeValues().get(":ownerId").s());
    }

    @Test
    void listByOwner_ownerId_filtersOutDeletedItemsOnly() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        DynamoDbUrlRepository repository = repositoryReturningEmptyPage(dynamoDb);

        repository.listByOwner("u1", 20, null);

        QueryRequest request = capturedQueryRequest(dynamoDb);
        assertEquals("#status <> :deleted", request.filterExpression());
        assertEquals("DELETED", request.expressionAttributeValues().get(":deleted").s());
        assertFalse(request.expressionAttributeValues().containsKey(":visibility"));
        assertFalse(request.expressionAttributeNames().containsKey("#visibility"));
    }

    @Test
    void listByOwner_ownerId_setsScanIndexForwardFalseForNewestFirst() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        DynamoDbUrlRepository repository = repositoryReturningEmptyPage(dynamoDb);

        repository.listByOwner("u1", 20, null);

        QueryRequest request = capturedQueryRequest(dynamoDb);
        assertFalse(request.scanIndexForward());
    }

    @Test
    void listByOwner_limitProvided_appliesLimitToQuery() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        DynamoDbUrlRepository repository = repositoryReturningEmptyPage(dynamoDb);

        repository.listByOwner("u1", 5, null);

        QueryRequest request = capturedQueryRequest(dynamoDb);
        assertEquals(5, request.limit().intValue());
    }

    @Test
    void listByOwner_nullCursor_omitsExclusiveStartKey() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        DynamoDbUrlRepository repository = repositoryReturningEmptyPage(dynamoDb);

        repository.listByOwner("u1", 20, null);

        QueryRequest request = capturedQueryRequest(dynamoDb);
        assertFalse(request.hasExclusiveStartKey());
    }

    @Test
    void listByOwner_cursorFromPreviousPage_passesDecodedExclusiveStartKey() {
        Map<String, AttributeValue> lastEvaluatedKey = Map.of(
                ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"),
                ShortUrlAttributes.SK, AttributeValue.fromS("METADATA"),
                ShortUrlAttributes.GSI1_PK, AttributeValue.fromS("USER#u1"),
                ShortUrlAttributes.GSI1_SK, AttributeValue.fromS("URL#2026-01-01T00:00:00.000000000Z#abc1234"));
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).lastEvaluatedKey(lastEvaluatedKey).build());
        DynamoDbUrlRepository repository = repository(dynamoDb);

        UrlPage firstPage = repository.listByOwner("u1", 20, null);
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build());
        repository.listByOwner("u1", 20, firstPage.nextCursor());

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb, times(2)).query(captor.capture());
        assertEquals(lastEvaluatedKey, captor.getAllValues().get(1).exclusiveStartKey());
    }

    @Test
    void listByOwner_queryReturnsItems_mapsResultsToShortUrl() {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"));
        item.put(ShortUrlAttributes.LONG_URL, AttributeValue.fromS("https://example.com/x"));
        item.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS("PRIVATE"));
        item.put(ShortUrlAttributes.STATUS, AttributeValue.fromS("ACTIVE"));
        item.put(ShortUrlAttributes.CREATED_AT, AttributeValue.fromS("2026-01-01T00:00:00.000000000Z"));
        item.put(ShortUrlAttributes.OWNER_ID, AttributeValue.fromS("USER#u1"));
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of(item)).build());
        DynamoDbUrlRepository repository = repository(dynamoDb);

        UrlPage page = repository.listByOwner("u1", 20, null);

        assertEquals(1, page.items().size());
        ShortUrl shortUrl = page.items().get(0);
        assertEquals("abc1234", shortUrl.shortCode());
        assertEquals("https://example.com/x", shortUrl.longUrl());
        assertEquals("u1", shortUrl.ownerId());
        assertEquals(Visibility.PRIVATE, shortUrl.visibility());
        assertEquals(UrlStatus.ACTIVE, shortUrl.status());
    }

    @Test
    void listByOwner_noLastEvaluatedKey_returnsNullNextCursor() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        DynamoDbUrlRepository repository = repositoryReturningEmptyPage(dynamoDb);

        UrlPage page = repository.listByOwner("u1", 20, null);

        assertNull(page.nextCursor());
    }

    @Test
    void listByOwner_lastEvaluatedKeyPresent_returnsEncodedNextCursor() {
        Map<String, AttributeValue> lastEvaluatedKey = Map.of(
                ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"),
                ShortUrlAttributes.SK, AttributeValue.fromS("METADATA"),
                ShortUrlAttributes.GSI1_PK, AttributeValue.fromS("USER#u1"),
                ShortUrlAttributes.GSI1_SK, AttributeValue.fromS("URL#2026-01-01T00:00:00.000000000Z#abc1234"));
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).lastEvaluatedKey(lastEvaluatedKey).build());
        DynamoDbUrlRepository repository = repository(dynamoDb);

        UrlPage page = repository.listByOwner("u1", 20, null);

        assertFalse(page.nextCursor() == null || page.nextCursor().isBlank());
    }

    @Test
    void listByOwner_dynamoDbThrowsSdkException_wrapsAsUrlRepositoryException() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        RuntimeException cause = DynamoDbException.builder().message("service unavailable").build();
        when(dynamoDb.query(any(QueryRequest.class))).thenThrow(cause);
        DynamoDbUrlRepository repository = repository(dynamoDb);

        UrlRepositoryException thrown =
                assertThrows(UrlRepositoryException.class, () -> repository.listByOwner("u1", 20, null));

        assertSame(cause, thrown.getCause());
    }

    @Test
    void listByOwner_malformedCursor_throwsInvalidCursorExceptionWithoutQueryingDynamoDb() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        DynamoDbUrlRepository repository = repository(dynamoDb);

        assertThrows(InvalidCursorException.class, () -> repository.listByOwner("u1", 20, "not-valid-base64!!!"));

        verify(dynamoDb, never()).query(any(QueryRequest.class));
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
