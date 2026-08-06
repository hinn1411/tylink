package com.tylink.repository.dynamodb;

import com.tylink.models.ShortUrl;
import com.tylink.models.UrlStatus;
import com.tylink.models.Visibility;
import com.tylink.repository.UpdateOutcome;
import com.tylink.repository.UrlRepositoryException;
import com.tylink.repository.pagination.InvalidCursorException;
import com.tylink.repository.pagination.UrlPage;
import org.junit.jupiter.api.BeforeEach;
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

    private DynamoDbClient dynamoDb;
    private DynamoDbUrlRepository repository;

    @BeforeEach
    void setUp() {
        dynamoDb = mock(DynamoDbClient.class);
        repository = new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);
    }

    private Map<String, AttributeValue> savedItem(ShortUrl shortUrl) {
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());

        repository.save(shortUrl);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        return captor.getValue().item();
    }

    private void stubEmptyQueryResponse() {
        when(dynamoDb.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of()).build());
    }

    private QueryRequest capturedQueryRequest() {
        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb).query(captor.capture());
        return captor.getValue();
    }

    @Test
    void save_validShortUrl_putsItemIntoConfiguredTable() {
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());

        repository.save(ShortUrl.create("abc1234", "https://example.com/x", null, Visibility.PUBLIC));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        assertEquals(TABLE_NAME, captor.getValue().tableName());
    }

    @Test
    void save_validShortUrl_putsCorePkSkAndStatusFields() {
        Map<String, AttributeValue> item = savedItem(ShortUrl.create("abc1234", "https://example.com/x", null, Visibility.PUBLIC));

        assertEquals("URL#abc1234", item.get(ShortUrlAttributes.PK).s());
        assertEquals("METADATA", item.get(ShortUrlAttributes.SK).s());
        assertEquals("ACTIVE", item.get(ShortUrlAttributes.STATUS).s());
        assertEquals("https://example.com/x", item.get(ShortUrlAttributes.LONG_URL).s());
        assertTrue(item.containsKey(ShortUrlAttributes.CREATED_AT));
        assertFalse(item.get(ShortUrlAttributes.CREATED_AT).s().isBlank());
    }

    @Test
    void save_validShortUrl_omitsUpdatedAtAndDeletedAtWhenNeverUpdated() {
        Map<String, AttributeValue> item = savedItem(ShortUrl.create("abc1234", "https://example.com/x", null, Visibility.PUBLIC));

        assertFalse(item.containsKey(ShortUrlAttributes.UPDATED_AT));
        assertFalse(item.containsKey(ShortUrlAttributes.DELETED_AT));
    }

    @Test
    void save_publicVisibility_reflectsPublicInItem() {
        Map<String, AttributeValue> item = savedItem(ShortUrl.create("abc1234", "https://example.com/x", null, Visibility.PUBLIC));

        assertEquals("PUBLIC", item.get(ShortUrlAttributes.VISIBILITY).s());
    }

    @Test
    void save_privateVisibility_reflectsPrivateInItem() {
        Map<String, AttributeValue> item = savedItem(ShortUrl.create("abc1234", "https://example.com/x", null, Visibility.PRIVATE));

        assertEquals("PRIVATE", item.get(ShortUrlAttributes.VISIBILITY).s());
    }

    @Test
    void save_ownerIdNull_omitsOwnerAndGsi1Keys() {
        Map<String, AttributeValue> item = savedItem(ShortUrl.create("abc1234", "https://example.com/x", null, Visibility.PUBLIC));

        assertFalse(item.containsKey(ShortUrlAttributes.OWNER_ID));
        assertFalse(item.containsKey(ShortUrlAttributes.GSI1_PK));
        assertFalse(item.containsKey(ShortUrlAttributes.GSI1_SK));
    }

    @Test
    void save_ownerIdPresent_includesOwnerAndGsi1Keys() {
        ShortUrl shortUrl = ShortUrl.create("abc1234", "https://example.com/x", "u1", Visibility.PRIVATE);

        Map<String, AttributeValue> item = savedItem(shortUrl);

        assertEquals("USER#u1", item.get(ShortUrlAttributes.OWNER_ID).s());
        assertEquals("USER#u1", item.get(ShortUrlAttributes.GSI1_PK).s());
        assertEquals("URL#" + shortUrl.createdAt() + "#abc1234", item.get(ShortUrlAttributes.GSI1_SK).s());
    }

    @Test
    void save_dynamoDbThrowsSdkException_wrapsAsUrlRepositoryException() {
        RuntimeException cause = DynamoDbException.builder().message("service unavailable").build();
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenThrow(cause);
        ShortUrl shortUrl = ShortUrl.create("abc1234", "https://example.com/x", null, Visibility.PUBLIC);

        UrlRepositoryException thrown = assertThrows(UrlRepositoryException.class, () -> repository.save(shortUrl));

        assertSame(cause, thrown.getCause());
    }

    @Test
    void findByShortCode_validShortCode_usesCorrectKey() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());

        repository.findByShortCode("abc1234");

        ArgumentCaptor<GetItemRequest> captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamoDb).getItem(captor.capture());
        assertEquals(TABLE_NAME, captor.getValue().tableName());
        assertEquals("URL#abc1234", captor.getValue().key().get(ShortUrlAttributes.PK).s());
        assertEquals("METADATA", captor.getValue().key().get(ShortUrlAttributes.SK).s());
    }

    @Test
    void findByShortCode_itemDoesNotExist_returnsNull() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());

        ShortUrl shortUrl = repository.findByShortCode("abc1234");

        assertNull(shortUrl);
    }

    @Test
    void findByShortCode_itemExists_mapsItemBackToShortUrlStrippingOwnerPrefix() {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"));
        item.put(ShortUrlAttributes.LONG_URL, AttributeValue.fromS("https://example.com/x"));
        item.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS("PRIVATE"));
        item.put(ShortUrlAttributes.STATUS, AttributeValue.fromS("ACTIVE"));
        item.put(ShortUrlAttributes.CREATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));
        item.put(ShortUrlAttributes.UPDATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));
        item.put(ShortUrlAttributes.OWNER_ID, AttributeValue.fromS("USER#u1"));
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        ShortUrl shortUrl = repository.findByShortCode("abc1234");

        assertEquals("abc1234", shortUrl.shortCode());
        assertEquals("https://example.com/x", shortUrl.longUrl());
        assertEquals("u1", shortUrl.ownerId());
        assertEquals(Visibility.PRIVATE, shortUrl.visibility());
        assertEquals(UrlStatus.ACTIVE, shortUrl.status());
        assertEquals("2026-01-01T00:00:00Z", shortUrl.createdAt());
        assertEquals("2026-01-01T00:00:00Z", shortUrl.updatedAt());
        assertNull(shortUrl.deletedAt());
    }

    @Test
    void findByShortCode_ownerIdAttributeAbsent_returnsNullOwnerId() {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"));
        item.put(ShortUrlAttributes.LONG_URL, AttributeValue.fromS("https://example.com/x"));
        item.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS("PUBLIC"));
        item.put(ShortUrlAttributes.STATUS, AttributeValue.fromS("ACTIVE"));
        item.put(ShortUrlAttributes.CREATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));
        item.put(ShortUrlAttributes.UPDATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        ShortUrl shortUrl = repository.findByShortCode("abc1234");

        assertNull(shortUrl.ownerId());
    }

    @Test
    void findByShortCode_updatedAtAttributeAbsent_returnsNullUpdatedAt() {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"));
        item.put(ShortUrlAttributes.LONG_URL, AttributeValue.fromS("https://example.com/x"));
        item.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS("PUBLIC"));
        item.put(ShortUrlAttributes.STATUS, AttributeValue.fromS("ACTIVE"));
        item.put(ShortUrlAttributes.CREATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        ShortUrl shortUrl = repository.findByShortCode("abc1234");

        assertNull(shortUrl.updatedAt());
    }

    @Test
    void findByShortCode_statusDeleted_parsesDeletedStatus() {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"));
        item.put(ShortUrlAttributes.LONG_URL, AttributeValue.fromS("https://example.com/x"));
        item.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS("PUBLIC"));
        item.put(ShortUrlAttributes.STATUS, AttributeValue.fromS("DELETED"));
        item.put(ShortUrlAttributes.CREATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));
        item.put(ShortUrlAttributes.UPDATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));
        item.put(ShortUrlAttributes.DELETED_AT, AttributeValue.fromS("2026-01-02T00:00:00Z"));
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        ShortUrl shortUrl = repository.findByShortCode("abc1234");

        assertEquals(UrlStatus.DELETED, shortUrl.status());
        assertEquals("2026-01-02T00:00:00Z", shortUrl.deletedAt());
    }

    @Test
    void findByShortCode_itemMissingRequiredAttribute_throwsUrlRepositoryExceptionNamingIt() {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"));
        item.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS("PUBLIC"));
        item.put(ShortUrlAttributes.STATUS, AttributeValue.fromS("ACTIVE"));
        item.put(ShortUrlAttributes.CREATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));
        // LONG_URL intentionally omitted to simulate a corrupted/partially written item
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        UrlRepositoryException thrown =
                assertThrows(UrlRepositoryException.class, () -> repository.findByShortCode("abc1234"));

        assertTrue(thrown.getMessage().contains(ShortUrlAttributes.LONG_URL));
    }

    @Test
    void findByShortCode_dynamoDbThrowsSdkException_wrapsAsUrlRepositoryException() {
        RuntimeException cause = DynamoDbException.builder().message("service unavailable").build();
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenThrow(cause);

        UrlRepositoryException thrown =
                assertThrows(UrlRepositoryException.class, () -> repository.findByShortCode("abc1234"));

        assertSame(cause, thrown.getCause());
    }

    @Test
    void markDeleted_itemExistsAndOwnedByCaller_sendsConditionalUpdateWithDeletedStatusAndOwnerCondition() {
        when(dynamoDb.updateItem(any(UpdateItemRequest.class))).thenReturn(UpdateItemResponse.builder().build());

        boolean deleted = repository.markDeleted("abc1234", "u1");

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDb).updateItem(captor.capture());
        UpdateItemRequest request = captor.getValue();
        assertTrue(deleted);
        assertEquals(TABLE_NAME, request.tableName());
        assertEquals("URL#abc1234", request.key().get(ShortUrlAttributes.PK).s());
        assertEquals("METADATA", request.key().get(ShortUrlAttributes.SK).s());
        assertEquals("SET #status = :deleted, #deletedAt = :deletedAt", request.updateExpression());
        assertEquals("attribute_exists(PK) AND #ownerId = :ownerId", request.conditionExpression());
        assertEquals(ShortUrlAttributes.STATUS, request.expressionAttributeNames().get("#status"));
        assertEquals(ShortUrlAttributes.DELETED_AT, request.expressionAttributeNames().get("#deletedAt"));
        assertEquals(ShortUrlAttributes.OWNER_ID, request.expressionAttributeNames().get("#ownerId"));
        assertEquals("DELETED", request.expressionAttributeValues().get(":deleted").s());
        assertFalse(request.expressionAttributeValues().get(":deletedAt").s().isBlank());
        assertEquals("USER#u1", request.expressionAttributeValues().get(":ownerId").s());
    }

    @Test
    void markDeleted_conditionalCheckFails_returnsFalseWithoutThrowing() {
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("condition failed").build());

        boolean deleted = repository.markDeleted("abc1234", "u1");

        assertFalse(deleted);
    }

    @Test
    void markDeleted_dynamoDbThrowsOtherSdkException_wrapsAsUrlRepositoryException() {
        RuntimeException cause = DynamoDbException.builder().message("service unavailable").build();
        when(dynamoDb.updateItem(any(UpdateItemRequest.class))).thenThrow(cause);

        UrlRepositoryException thrown =
                assertThrows(UrlRepositoryException.class, () -> repository.markDeleted("abc1234", "u1"));

        assertSame(cause, thrown.getCause());
    }

    @Test
    void updateLongUrl_itemExistsOwnedAndActive_sendsConditionalUpdateAndReturnsUpdatedShortUrl() {
        Map<String, AttributeValue> newAttributes = new HashMap<>();
        newAttributes.put(ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"));
        newAttributes.put(ShortUrlAttributes.LONG_URL, AttributeValue.fromS("https://example.com/new"));
        newAttributes.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS("PUBLIC"));
        newAttributes.put(ShortUrlAttributes.STATUS, AttributeValue.fromS("ACTIVE"));
        newAttributes.put(ShortUrlAttributes.CREATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));
        newAttributes.put(ShortUrlAttributes.UPDATED_AT, AttributeValue.fromS("2026-01-02T00:00:00Z"));
        newAttributes.put(ShortUrlAttributes.OWNER_ID, AttributeValue.fromS("USER#u1"));
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(UpdateItemResponse.builder().attributes(newAttributes).build());

        UpdateOutcome outcome = repository.updateLongUrl("abc1234", "u1", "https://example.com/new");

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDb).updateItem(captor.capture());
        UpdateItemRequest request = captor.getValue();
        assertEquals(TABLE_NAME, request.tableName());
        assertEquals("URL#abc1234", request.key().get(ShortUrlAttributes.PK).s());
        assertEquals("SET #longUrl = :longUrl, #updatedAt = :updatedAt", request.updateExpression());
        assertEquals("attribute_exists(PK) AND #ownerId = :ownerId AND #status = :active", request.conditionExpression());
        assertEquals("https://example.com/new", request.expressionAttributeValues().get(":longUrl").s());
        assertEquals("USER#u1", request.expressionAttributeValues().get(":ownerId").s());
        assertEquals("ACTIVE", request.expressionAttributeValues().get(":active").s());
        assertEquals(UpdateOutcome.Status.UPDATED, outcome.status());
        assertEquals("https://example.com/new", outcome.shortUrl().longUrl());
        assertEquals("u1", outcome.shortUrl().ownerId());
    }

    @Test
    void updateLongUrl_conditionalCheckFailsAndItemDoesNotExist_returnsNotFound() {
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("condition failed").build());
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());

        UpdateOutcome outcome = repository.updateLongUrl("abc1234", "u1", "https://example.com/new");

        assertEquals(UpdateOutcome.Status.NOT_FOUND, outcome.status());
    }

    @Test
    void updateLongUrl_conditionalCheckFailsAndOwnerMismatches_returnsNotFoundHidingOwnership() {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"));
        item.put(ShortUrlAttributes.LONG_URL, AttributeValue.fromS("https://example.com/x"));
        item.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS("PUBLIC"));
        item.put(ShortUrlAttributes.STATUS, AttributeValue.fromS("ACTIVE"));
        item.put(ShortUrlAttributes.CREATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));
        item.put(ShortUrlAttributes.UPDATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));
        item.put(ShortUrlAttributes.OWNER_ID, AttributeValue.fromS("USER#someone-else"));
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("condition failed").build());
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());

        UpdateOutcome outcome = repository.updateLongUrl("abc1234", "u1", "https://example.com/new");

        assertEquals(UpdateOutcome.Status.NOT_FOUND, outcome.status());
    }

    @Test
    void updateLongUrl_conditionalCheckFailsAndItemAlreadyDeleted_returnsAlreadyDeletedWithCurrentState() {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"));
        item.put(ShortUrlAttributes.LONG_URL, AttributeValue.fromS("https://example.com/x"));
        item.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS("PUBLIC"));
        item.put(ShortUrlAttributes.STATUS, AttributeValue.fromS("DELETED"));
        item.put(ShortUrlAttributes.CREATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));
        item.put(ShortUrlAttributes.UPDATED_AT, AttributeValue.fromS("2026-01-01T00:00:00Z"));
        item.put(ShortUrlAttributes.DELETED_AT, AttributeValue.fromS("2026-01-03T00:00:00Z"));
        item.put(ShortUrlAttributes.OWNER_ID, AttributeValue.fromS("USER#u1"));
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("condition failed").build());
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());

        UpdateOutcome outcome = repository.updateLongUrl("abc1234", "u1", "https://example.com/new");

        assertEquals(UpdateOutcome.Status.ALREADY_DELETED, outcome.status());
        assertEquals("https://example.com/x", outcome.shortUrl().longUrl());
        assertEquals("2026-01-03T00:00:00Z", outcome.shortUrl().deletedAt());
    }

    @Test
    void updateLongUrl_dynamoDbThrowsOtherSdkException_wrapsAsUrlRepositoryException() {
        RuntimeException cause = DynamoDbException.builder().message("service unavailable").build();
        when(dynamoDb.updateItem(any(UpdateItemRequest.class))).thenThrow(cause);

        UrlRepositoryException thrown = assertThrows(UrlRepositoryException.class,
                () -> repository.updateLongUrl("abc1234", "u1", "https://example.com/new"));

        assertSame(cause, thrown.getCause());
    }

    @Test
    void listByOwner_ownerId_queriesGsi1WithAliasedOwnerPrefixedKeyCondition() {
        stubEmptyQueryResponse();

        repository.listByOwner("u1", 20, null);

        QueryRequest request = capturedQueryRequest();
        assertEquals(TABLE_NAME, request.tableName());
        assertEquals(ShortUrlAttributes.GSI1_INDEX_NAME, request.indexName());
        assertEquals("#gsi1_pk = :ownerId", request.keyConditionExpression());
        assertEquals(ShortUrlAttributes.GSI1_PK, request.expressionAttributeNames().get("#gsi1_pk"));
        assertEquals("USER#u1", request.expressionAttributeValues().get(":ownerId").s());
    }

    @Test
    void listByOwner_ownerId_filtersOutDeletedItemsOnly() {
        stubEmptyQueryResponse();

        repository.listByOwner("u1", 20, null);

        QueryRequest request = capturedQueryRequest();
        assertEquals("#status <> :deleted", request.filterExpression());
        assertEquals("DELETED", request.expressionAttributeValues().get(":deleted").s());
        assertFalse(request.expressionAttributeValues().containsKey(":visibility"));
        assertFalse(request.expressionAttributeNames().containsKey("#visibility"));
    }

    @Test
    void listByOwner_ownerId_setsScanIndexForwardFalseForNewestFirst() {
        stubEmptyQueryResponse();

        repository.listByOwner("u1", 20, null);

        QueryRequest request = capturedQueryRequest();
        assertFalse(request.scanIndexForward());
    }

    @Test
    void listByOwner_limitProvided_appliesLimitToQuery() {
        stubEmptyQueryResponse();

        repository.listByOwner("u1", 5, null);

        QueryRequest request = capturedQueryRequest();
        assertEquals(5, request.limit().intValue());
    }

    @Test
    void listByOwner_nullCursor_omitsExclusiveStartKey() {
        stubEmptyQueryResponse();

        repository.listByOwner("u1", 20, null);

        QueryRequest request = capturedQueryRequest();
        assertFalse(request.hasExclusiveStartKey());
    }

    @Test
    void listByOwner_cursorFromPreviousPage_passesDecodedExclusiveStartKey() {
        Map<String, AttributeValue> lastEvaluatedKey = Map.of(
                ShortUrlAttributes.PK, AttributeValue.fromS("URL#abc1234"),
                ShortUrlAttributes.SK, AttributeValue.fromS("METADATA"),
                ShortUrlAttributes.GSI1_PK, AttributeValue.fromS("USER#u1"),
                ShortUrlAttributes.GSI1_SK, AttributeValue.fromS("URL#2026-01-01T00:00:00.000000000Z#abc1234"));
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).lastEvaluatedKey(lastEvaluatedKey).build());
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
        item.put(ShortUrlAttributes.UPDATED_AT, AttributeValue.fromS("2026-01-01T00:00:00.000000000Z"));
        item.put(ShortUrlAttributes.OWNER_ID, AttributeValue.fromS("USER#u1"));
        when(dynamoDb.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of(item)).build());

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
        stubEmptyQueryResponse();

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
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).lastEvaluatedKey(lastEvaluatedKey).build());

        UrlPage page = repository.listByOwner("u1", 20, null);

        assertFalse(page.nextCursor() == null || page.nextCursor().isBlank());
    }

    @Test
    void listByOwner_dynamoDbThrowsSdkException_wrapsAsUrlRepositoryException() {
        RuntimeException cause = DynamoDbException.builder().message("service unavailable").build();
        when(dynamoDb.query(any(QueryRequest.class))).thenThrow(cause);

        UrlRepositoryException thrown =
                assertThrows(UrlRepositoryException.class, () -> repository.listByOwner("u1", 20, null));

        assertSame(cause, thrown.getCause());
    }

    @Test
    void listByOwner_malformedCursor_throwsInvalidCursorExceptionWithoutQueryingDynamoDb() {
        assertThrows(InvalidCursorException.class, () -> repository.listByOwner("u1", 20, "not-valid-base64!!!"));

        verify(dynamoDb, never()).query(any(QueryRequest.class));
    }
}
