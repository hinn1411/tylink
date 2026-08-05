package com.tylink.repository.dynamodb;

import com.tylink.models.ShortUrl;
import com.tylink.models.UrlStatus;
import com.tylink.models.Visibility;
import com.tylink.repository.UrlRepository;
import com.tylink.repository.UrlRepositoryException;
import com.tylink.repository.pagination.UrlPage;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

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

    @Override
    public boolean markDeleted(String shortCode, String ownerId) {
        try {
            dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            ShortUrlAttributes.PK, AttributeValue.fromS(ShortUrlAttributes.URL_KEY_PREFIX + shortCode),
                            ShortUrlAttributes.SK, AttributeValue.fromS(ShortUrlAttributes.SK_METADATA)))
                    .updateExpression("SET #status = :deleted")
                    .conditionExpression("attribute_exists(PK) AND #ownerId = :ownerId")
                    .expressionAttributeNames(Map.of(
                            "#status", ShortUrlAttributes.STATUS,
                            "#ownerId", ShortUrlAttributes.OWNER_ID))
                    .expressionAttributeValues(Map.of(
                            ":deleted", AttributeValue.fromS(UrlStatus.DELETED.name()),
                            ":ownerId", AttributeValue.fromS(ShortUrlAttributes.USER_KEY_PREFIX + ownerId)))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        } catch (SdkException e) {
            throw new UrlRepositoryException(
                    "Failed to mark shortCode=" + shortCode + " deleted in table " + tableName, e);
        }
    }

    private static ShortUrl toShortUrl(Map<String, AttributeValue> item) {
        String shortCode = requiredAttribute(item, ShortUrlAttributes.PK)
                .substring(ShortUrlAttributes.URL_KEY_PREFIX.length());
        String ownerId = Optional.ofNullable(item.get(ShortUrlAttributes.OWNER_ID))
                .map(AttributeValue::s)
                .map(prefixed -> prefixed.substring(ShortUrlAttributes.USER_KEY_PREFIX.length()))
                .orElse(null);
        return new ShortUrl(
                shortCode,
                requiredAttribute(item, ShortUrlAttributes.LONG_URL),
                ownerId,
                Visibility.parse(requiredAttribute(item, ShortUrlAttributes.VISIBILITY)),
                UrlStatus.parse(requiredAttribute(item, ShortUrlAttributes.STATUS)),
                requiredAttribute(item, ShortUrlAttributes.CREATED_AT));
    }

    // Returned attribute from DynamoDB does not match ShortUrl schema (e.g. a partially
    // written item from a bug, a manual console edit, or a bad migration).
    // Throw exception with attribute name to investigate instead of NullPointerException
    private static String requiredAttribute(Map<String, AttributeValue> item, String attributeName) {
        return Optional.ofNullable(item.get(attributeName))
                .map(AttributeValue::s)
                .orElseThrow(() -> new UrlRepositoryException(
                        "Corrupted item: missing required attribute " + attributeName));
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
