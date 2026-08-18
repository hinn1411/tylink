package com.tylink.repository.dynamodb;

import com.tylink.models.ShortUrl;
import com.tylink.models.UrlStatus;
import com.tylink.models.Visibility;
import com.tylink.repository.UpdateOutcome;
import com.tylink.repository.UrlRepository;
import com.tylink.repository.UrlRepositoryException;
import com.tylink.repository.pagination.UrlPage;
import com.tylink.utils.SnapStartWarmup;
import com.tylink.utils.TimestampUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class DynamoDbUrlRepository implements UrlRepository {

    private static final Logger log = LogManager.getLogger(DynamoDbUrlRepository.class);

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoDbUrlRepository(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
        SnapStartWarmup.registerAfterRestore(this::warmUp);
    }

    private void warmUp() {
        try {
            dynamoDb.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            ShortUrlAttributes.PK, AttributeValue.fromS(ShortUrlAttributes.URL_KEY_PREFIX + "SNAPSTARTWARMUP"),
                            ShortUrlAttributes.SK, AttributeValue.fromS(ShortUrlAttributes.SK_METADATA)))
                    .build());
        } catch (SdkException e) {
            log.warn("SnapStart warm-up GetItem failed, continuing anyway", e);
        }
    }

    @Override
    public boolean save(ShortUrl shortUrl) {
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(toItem(shortUrl))
                    .conditionExpression("attribute_not_exists(PK)")
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
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
                    .updateExpression("SET #status = :deleted, #deletedAt = :deletedAt")
                    .conditionExpression("attribute_exists(PK) AND #ownerId = :ownerId")
                    .expressionAttributeNames(Map.of(
                            "#status", ShortUrlAttributes.STATUS,
                            "#deletedAt", ShortUrlAttributes.DELETED_AT,
                            "#ownerId", ShortUrlAttributes.OWNER_ID))
                    .expressionAttributeValues(Map.of(
                            ":deleted", AttributeValue.fromS(UrlStatus.DELETED.name()),
                            ":deletedAt", AttributeValue.fromS(TimestampUtils.now()),
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

    @Override
    public UpdateOutcome updateLongUrl(String shortCode, String ownerId, String longUrl) {
        try {
            UpdateItemResponse response = dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            ShortUrlAttributes.PK, AttributeValue.fromS(ShortUrlAttributes.URL_KEY_PREFIX + shortCode),
                            ShortUrlAttributes.SK, AttributeValue.fromS(ShortUrlAttributes.SK_METADATA)))
                    .updateExpression("SET #longUrl = :longUrl, #updatedAt = :updatedAt")
                    .conditionExpression("attribute_exists(PK) AND #ownerId = :ownerId AND #status = :active")
                    .expressionAttributeNames(Map.of(
                            "#longUrl", ShortUrlAttributes.LONG_URL,
                            "#updatedAt", ShortUrlAttributes.UPDATED_AT,
                            "#ownerId", ShortUrlAttributes.OWNER_ID,
                            "#status", ShortUrlAttributes.STATUS))
                    .expressionAttributeValues(Map.of(
                            ":longUrl", AttributeValue.fromS(longUrl),
                            ":updatedAt", AttributeValue.fromS(TimestampUtils.now()),
                            ":ownerId", AttributeValue.fromS(ShortUrlAttributes.USER_KEY_PREFIX + ownerId),
                            ":active", AttributeValue.fromS(UrlStatus.ACTIVE.name())))
                    .returnValues(ReturnValue.ALL_NEW)
                    .build());
            return UpdateOutcome.updated(toShortUrl(response.attributes()));
        } catch (ConditionalCheckFailedException e) {
            return resolveUpdateFailure(shortCode, ownerId);
        } catch (SdkException e) {
            throw new UrlRepositoryException(
                    "Failed to update shortCode=" + shortCode + " in table " + tableName, e);
        }
    }

    // ConditionalCheckFailedException doesn't say why: not found, wrong owner, and DELETED all
    // look the same, so re-read to tell "yours but deleted" (410) apart from "not yours" (404).
    private UpdateOutcome resolveUpdateFailure(String shortCode, String ownerId) {
        ShortUrl existing = findByShortCode(shortCode);
        if (Objects.isNull(existing) || !Objects.equals(existing.ownerId(), ownerId)) {
            return UpdateOutcome.notFound();
        }
        return UpdateOutcome.alreadyDeleted(existing);
    }

    private static ShortUrl toShortUrl(Map<String, AttributeValue> item) {
        String shortCode = requiredAttribute(item, ShortUrlAttributes.PK)
                .substring(ShortUrlAttributes.URL_KEY_PREFIX.length());
        String longUrl = requiredAttribute(item, ShortUrlAttributes.LONG_URL);
        Visibility visibility = Visibility.parse(requiredAttribute(item, ShortUrlAttributes.VISIBILITY));
        UrlStatus status = UrlStatus.parse(requiredAttribute(item, ShortUrlAttributes.STATUS));
        String createdAt = requiredAttribute(item, ShortUrlAttributes.CREATED_AT);
        String ownerId = optionalAttribute(item, ShortUrlAttributes.OWNER_ID)
                .map(prefixed -> prefixed.substring(ShortUrlAttributes.USER_KEY_PREFIX.length()))
                .orElse(null);
        String updatedAt = optionalAttribute(item, ShortUrlAttributes.UPDATED_AT).orElse(null);
        String deletedAt = optionalAttribute(item, ShortUrlAttributes.DELETED_AT).orElse(null);

        return new ShortUrl(shortCode, longUrl, ownerId, visibility, status, createdAt, updatedAt, deletedAt);
    }


    // Attributes required by schema, exception means application error
    private static String requiredAttribute(Map<String, AttributeValue> item, String attributeName) {
        return optionalAttribute(item, attributeName)
                .orElseThrow(() -> new UrlRepositoryException(
                        "Corrupted item: missing required attribute " + attributeName));
    }

    // Optionally attributes exist in schema
    private static Optional<String> optionalAttribute(Map<String, AttributeValue> item, String attributeName) {
        return Optional.ofNullable(item.get(attributeName)).map(AttributeValue::s);
    }

    private static Map<String, AttributeValue> toItem(ShortUrl shortUrl) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ShortUrlAttributes.PK, AttributeValue.fromS(ShortUrlAttributes.URL_KEY_PREFIX + shortUrl.shortCode()));
        item.put(ShortUrlAttributes.SK, AttributeValue.fromS(ShortUrlAttributes.SK_METADATA));
        item.put(ShortUrlAttributes.LONG_URL, AttributeValue.fromS(shortUrl.longUrl()));
        item.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS(shortUrl.visibility().name()));
        item.put(ShortUrlAttributes.STATUS, AttributeValue.fromS(shortUrl.status().name()));
        item.put(ShortUrlAttributes.CREATED_AT, AttributeValue.fromS(shortUrl.createdAt()));
        if (shortUrl.updatedAt() != null) {
            item.put(ShortUrlAttributes.UPDATED_AT, AttributeValue.fromS(shortUrl.updatedAt()));
        }
        if (shortUrl.deletedAt() != null) {
            item.put(ShortUrlAttributes.DELETED_AT, AttributeValue.fromS(shortUrl.deletedAt()));
        }

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
