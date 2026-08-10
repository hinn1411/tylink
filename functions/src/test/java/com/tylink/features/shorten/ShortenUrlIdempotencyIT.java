package com.tylink.features.shorten;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.tylink.repository.dynamodb.DynamoDbUrlRepository;
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
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.lambda.powertools.idempotency.Idempotency;
import software.amazon.lambda.powertools.idempotency.IdempotencyConfig;
import software.amazon.lambda.powertools.idempotency.persistence.dynamodb.DynamoDBPersistenceStore;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies the real Idempotency-Key behavior of ShortenUrlHandler against DynamoDB Local, not a
 * mocked UrlRepository (N4 covers business logic; this covers the infra-dependent replay/conflict
 * behavior Powertools provides — TTL expiry and the concurrent-in-progress race are out of scope,
 * both need real time/thread orchestration disproportionate to this test).
 */
@Testcontainers
class ShortenUrlIdempotencyIT {

    private static final String URL_TABLE_NAME = "UrlTable";
    private static final String IDEMPOTENCY_TABLE_NAME = "IdempotencyTable";

    @Container
    @SuppressWarnings("resource") // lifecycle managed by @Testcontainers/@Container, not a leak
    private static final GenericContainer<?> DYNAMODB_LOCAL =
            new GenericContainer<>("amazon/dynamodb-local:latest").withExposedPorts(8000);

    private static DynamoDbClient client;
    private static ShortenUrlHandler handler;

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
                .tableName(URL_TABLE_NAME)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build())
                .build());

        client.createTable(CreateTableRequest.builder()
                .tableName(IDEMPOTENCY_TABLE_NAME)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
                .build());

        Idempotency.config()
                .withPersistenceStore(DynamoDBPersistenceStore.builder()
                        .withTableName(IDEMPOTENCY_TABLE_NAME)
                        .withDynamoDbClient(client)
                        .build())
                .withConfig(IdempotencyConfig.builder()
                        .withEventKeyJMESPath("idempotencyKey")
                        .withPayloadValidationJMESPath("longUrl")
                        .withExpiration(Duration.ofMinutes(5))
                        .build())
                .configure();

        handler = new ShortenUrlHandler(new DynamoDbUrlRepository(client, URL_TABLE_NAME));
    }

    @AfterAll
    static void tearDown() {
        client.close();
    }

    private static APIGatewayV2HTTPEvent createEvent(String idempotencyKey, String longUrl) {
        return APIGatewayV2HTTPEvent.builder()
                .withBody("{\"longUrl\": \"" + longUrl + "\"}")
                .withHeaders(Map.of("idempotency-key", idempotencyKey))
                .build();
    }

    @Test
    void handleRequest_sameKeySameLongUrl_replaysSameShortCode() {
        APIGatewayV2HTTPEvent event = createEvent("replay-key", "https://example.com/a");

        APIGatewayV2HTTPResponse first = handler.handleRequest(event, null);
        APIGatewayV2HTTPResponse second = handler.handleRequest(event, null);

        assertEquals(201, first.getStatusCode());
        assertEquals(first.getBody(), second.getBody());
    }

    @Test
    void handleRequest_sameKeyDifferentLongUrl_returns409() {
        APIGatewayV2HTTPResponse first = handler.handleRequest(createEvent("conflict-key", "https://example.com/b"), null);
        APIGatewayV2HTTPResponse second = handler.handleRequest(createEvent("conflict-key", "https://example.com/c"), null);

        assertEquals(201, first.getStatusCode());
        assertEquals(409, second.getStatusCode());
        assertNotEquals(first.getBody(), second.getBody());
    }
}
