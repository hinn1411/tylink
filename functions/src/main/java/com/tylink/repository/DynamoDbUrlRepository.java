package com.tylink.repository;

import com.tylink.model.ShortUrl;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;

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

    private static Map<String, AttributeValue> toItem(ShortUrl shortUrl) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ShortUrlAttributes.PK, AttributeValue.fromS(ShortUrlAttributes.URL_KEY_PREFIX + shortUrl.shortCode()));
        item.put(ShortUrlAttributes.SK, AttributeValue.fromS(ShortUrlAttributes.SK_METADATA));
        item.put(ShortUrlAttributes.LONG_URL, AttributeValue.fromS(shortUrl.longUrl()));
        item.put(ShortUrlAttributes.VISIBILITY, AttributeValue.fromS(shortUrl.visibility().name()));
        item.put(ShortUrlAttributes.STATUS, AttributeValue.fromS(ShortUrlAttributes.STATUS_ACTIVE));
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
