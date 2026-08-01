package com.tylink.shorten;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortenUrlHandlerTest {

    private static final String TABLE_NAME = "UrlTable";

    /**
     * Mimics what ExtractTokenAuthorizerHandler actually returns as its authorizer context after
     * verifying a token — see com.tylink.auth.ExtractTokenAuthorizerHandler.
     */
    private APIGatewayV2HTTPEvent eventWithOwnerId(String body, String ownerId) {
        APIGatewayV2HTTPEvent.RequestContext.Authorizer authorizer =
                APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
                        .withLambda(Map.of("ownerId", ownerId))
                        .build();
        APIGatewayV2HTTPEvent.RequestContext requestContext =
                APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withAuthorizer(authorizer)
                        .build();
        return APIGatewayV2HTTPEvent.builder()
                .withBody(body)
                .withRequestContext(requestContext)
                .build();
    }

    @Test
    void createsPublicUrlAnonymouslyWhenNoAuthenticatedCaller() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());
        ShortenUrlHandler handler = new ShortenUrlHandler(dynamoDb, TABLE_NAME);

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withBody("{\"longUrl\": \"https://example.com/some/very/long/path\"}")
                .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(201, response.getStatusCode());
        assertTrue(response.getBody().contains("\"visibility\":\"PUBLIC\""));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> item = captor.getValue().item();
        assertFalse(item.containsKey("ownerId"));
        assertFalse(item.containsKey("GSI1_PK"));
        assertFalse(item.containsKey("GSI1_SK"));
    }

    @Test
    void rejectsPrivateUrlWithNoAuthenticatedCaller() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        ShortenUrlHandler handler = new ShortenUrlHandler(dynamoDb, TABLE_NAME);

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withBody("{\"longUrl\": \"https://example.com/some/very/long/path\", \"visibility\": \"PRIVATE\"}")
                .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(401, response.getStatusCode());
    }

    @Test
    void createsPublicUrlAndTagsItWithOwnerFromAuthorizerContext() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());
        ShortenUrlHandler handler = new ShortenUrlHandler(dynamoDb, TABLE_NAME);

        APIGatewayV2HTTPEvent event = eventWithOwnerId(
                "{\"longUrl\": \"https://example.com/some/very/long/path\", \"visibility\": \"PUBLIC\"}",
                "11111111-1111-1111-1111-111111111111");

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(201, response.getStatusCode());
        assertTrue(response.getBody().contains("\"visibility\":\"PUBLIC\""));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> item = captor.getValue().item();
        assertEquals(TABLE_NAME, captor.getValue().tableName());
        assertEquals("METADATA", item.get("SK").s());
        assertEquals("PUBLIC", item.get("visibility").s());
        assertEquals("USER#11111111-1111-1111-1111-111111111111", item.get("ownerId").s());
        assertEquals(item.get("ownerId").s(), item.get("GSI1_PK").s());
        assertTrue(item.get("PK").s().startsWith("URL#"));
    }

    @Test
    void createsPrivateUrlOwnedByCaller() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());
        ShortenUrlHandler handler = new ShortenUrlHandler(dynamoDb, TABLE_NAME);

        APIGatewayV2HTTPEvent event = eventWithOwnerId(
                "{\"longUrl\": \"https://example.com/bobs-private-dashboard\", \"visibility\": \"PRIVATE\"}",
                "22222222-2222-2222-2222-222222222222");

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(201, response.getStatusCode());
        assertTrue(response.getBody().contains("\"visibility\":\"PRIVATE\""));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        assertEquals("PRIVATE", captor.getValue().item().get("visibility").s());
        assertEquals("USER#22222222-2222-2222-2222-222222222222", captor.getValue().item().get("ownerId").s());
    }

    @Test
    void rejectsMissingLongUrl() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        ShortenUrlHandler handler = new ShortenUrlHandler(dynamoDb, TABLE_NAME);

        APIGatewayV2HTTPEvent event = eventWithOwnerId("{}", "11111111-1111-1111-1111-111111111111");

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void rejectsInvalidVisibility() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        ShortenUrlHandler handler = new ShortenUrlHandler(dynamoDb, TABLE_NAME);

        APIGatewayV2HTTPEvent event = eventWithOwnerId(
                "{\"longUrl\": \"https://example.com/x\", \"visibility\": \"SECRET\"}",
                "11111111-1111-1111-1111-111111111111");

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(400, response.getStatusCode());
    }
}
