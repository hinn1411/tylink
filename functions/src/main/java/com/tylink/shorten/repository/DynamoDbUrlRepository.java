package com.tylink.shorten.repository;

import com.tylink.shorten.model.ShortUrl;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

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
                    .item(shortUrl.toItem())
                    .build());
        } catch (SdkException e) {
            throw new UrlRepositoryException(
                    "Failed to save shortCode=" + shortUrl.shortCode() + " to table " + tableName, e);
        }
    }
}
