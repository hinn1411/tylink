package com.tylink.repository;

import com.tylink.model.ShortUrl;
import com.tylink.model.UrlStatus;
import com.tylink.model.Visibility;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DynamoDbUrlRepository implements UrlRepository {

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoDbUrlRepository(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    @Override
    public void save(ShortUrl shortUrl) {
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(toItem(shortUrl))
                    .build());
        } catch (SdkException e) {
            throw new UrlRepositoryException(
                    "Failed to save shortCode=" + shortUrl.shortCode() + " to table " + tableName, e);
        }
    }

    @Override
    public ShortUrl findByShortCode(String shortCode) {
        try {
            GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            ShortUrlAttributes.PK, AttributeValue.fromS(ShortUrlAttributes.URL_KEY_PREFIX + shortCode),
                            ShortUrlAttributes.SK, AttributeValue.fromS(ShortUrlAttributes.SK_METADATA)))
                    .build());
            return response.hasItem() ? toShortUrl(response.item()) : null;
        } catch (SdkException e) {
            throw new UrlRepositoryException(
                    "Failed to read shortCode=" + shortCode + " from table " + tableName, e);
        }
    }

    @Override
    public UrlPage listByOwner(String ownerId, int limit, String cursor) {
        Map<String, AttributeValue> exclusiveStartKey = CursorCodec.decode(cursor);

        // Returns all of a user's URLs regardless of visibility (F3) — only soft-deleted items
        // are excluded. #status is aliased because STATUS is reserved; #gsi1_pk aliases GSI1_PK
        // the same way even though it isn't reserved, so both attributes in the request go
        // through one consistent expression-attribute-name convention.
        QueryRequest.Builder requestBuilder = QueryRequest.builder()
                .tableName(tableName)
                .indexName(ShortUrlAttributes.GSI1_INDEX_NAME)
                .keyConditionExpression("#gsi1_pk = :ownerId")
                .filterExpression("#status <> :deleted")
                .expressionAttributeNames(Map.of(
                        "#gsi1_pk", ShortUrlAttributes.GSI1_PK,
                        "#status", ShortUrlAttributes.STATUS))
                .expressionAttributeValues(Map.of(
                        ":ownerId", AttributeValue.fromS(ShortUrlAttributes.USER_KEY_PREFIX + ownerId),
                        ":deleted", AttributeValue.fromS(UrlStatus.DELETED.name())))
                .scanIndexForward(false)
                .limit(limit);
        if (exclusiveStartKey != null) {
            requestBuilder.exclusiveStartKey(exclusiveStartKey);
        }

        try {
            QueryResponse response = dynamoDb.query(requestBuilder.build());
            List<ShortUrl> items = response.items().stream()
                    .map(DynamoDbUrlRepository::toShortUrl)
                    .toList();
            String nextCursor = response.hasLastEvaluatedKey()
                    ? CursorCodec.encode(response.lastEvaluatedKey())
                    : null;
            return new UrlPage(items, nextCursor);
        } catch (SdkException e) {
            throw new UrlRepositoryException(
                    "Failed to list URLs for ownerId=" + ownerId + " from table " + tableName, e);
        }
    }

    private static ShortUrl toShortUrl(Map<String, AttributeValue> item) {
        String shortCode = item.get(ShortUrlAttributes.PK).s().substring(ShortUrlAttributes.URL_KEY_PREFIX.length());
        String ownerId = Optional.ofNullable(item.get(ShortUrlAttributes.OWNER_ID))
                .map(AttributeValue::s)
                .map(prefixed -> prefixed.substring(ShortUrlAttributes.USER_KEY_PREFIX.length()))
                .orElse(null);
        return new ShortUrl(
                shortCode,
                item.get(ShortUrlAttributes.LONG_URL).s(),
                ownerId,
                Visibility.parse(item.get(ShortUrlAttributes.VISIBILITY).s()),
                UrlStatus.parse(item.get(ShortUrlAttributes.STATUS).s()),
                item.get(ShortUrlAttributes.CREATED_AT).s());
    }

    private static Map<String, AttributeValue> toItem(ShortUrl shortUrl) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ShortUrlAttributes.PK, AttributeValue.fromS(ShortUrlAttributes.URL_KEY_PREFIX + shortUrl.shortCode()));
        item.put(ShortUrlAttributes.SK, AttributeValue.fromS(ShortUrlAttributes.SK_METADATA));
        item.put(ShortUrlAttributes.LONG_URL, AttributeValue.fromS(shortUrl.longUrl()));
        item.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS(shortUrl.visibility().name()));
        item.put(ShortUrlAttributes.STATUS, AttributeValue.fromS(shortUrl.status().name()));
        item.put(ShortUrlAttributes.CREATED_AT, AttributeValue.fromS(shortUrl.createdAt()));

        // Do not partition url for unauthenticated users
        if (shortUrl.ownerId() != null) {
            String owner = ShortUrlAttributes.USER_KEY_PREFIX + shortUrl.ownerId();
            item.put(ShortUrlAttributes.OWNER_ID, AttributeValue.fromS(owner));
            item.put(ShortUrlAttributes.GSI1_PK, AttributeValue.fromS(owner));
            item.put(ShortUrlAttributes.GSI1_SK,
                    AttributeValue.fromS(ShortUrlAttributes.URL_KEY_PREFIX + shortUrl.createdAt() + "#" + shortUrl.shortCode()));
        }
        return item;
    }
}
