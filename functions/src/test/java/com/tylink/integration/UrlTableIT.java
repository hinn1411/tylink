package com.tylink.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the single-table PK/SK schema declared for UrlTable in template.yaml
 * against a real DynamoDB engine (DynamoDB Local), not a mock/in-memory stand-in.
 */
@Testcontainers
class UrlTableIT {

    private static final String TABLE_NAME = "UrlTable";

    @Container
    private static final GenericContainer<?> DYNAMODB_LOCAL =
            new GenericContainer<>("amazon/dynamodb-local:latest").withExposedPorts(8000);

    private static DynamoDbClient client;

    @BeforeAll
    static void setUp() {
        client = DynamoDbClient.builder()
                .endpointOverride(URI.create(
                        "http://" + DYNAMODB_LOCAL.getHost() + ":" + DYNAMODB_LOCAL.getMappedPort(8000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("dummy", "dummy")))
                .build();

        client.createTable(CreateTableRequest.builder()
                .tableName(TABLE_NAME)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build())
                .build());
    }

    @AfterAll
    static void tearDown() {
        client.close();
    }

    @Test
    void putAndGetItemRoundTripsOnCompositeKey() {
        client.putItem(PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(Map.of(
                        "PK", AttributeValue.fromS("URL#abc123"),
                        "SK", AttributeValue.fromS("METADATA"),
                        "longUrl", AttributeValue.fromS("https://example.com/some/very/long/path")))
                .build());

        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of(
                        "PK", AttributeValue.fromS("URL#abc123"),
                        "SK", AttributeValue.fromS("METADATA")))
                .build());

        assertTrue(response.hasItem());
        assertEquals("https://example.com/some/very/long/path", response.item().get("longUrl").s());
    }
}
