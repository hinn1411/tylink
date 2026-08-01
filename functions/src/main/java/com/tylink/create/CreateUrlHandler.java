package com.tylink.create;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.tylink.auth.AuthUtils;
import com.tylink.create.model.CreateUrlRequest;
import com.tylink.create.model.ShortUrl;
import com.tylink.create.model.Visibility;
import com.tylink.create.util.ShortCodeGenerator;
import com.tylink.util.RequestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.utils.StringUtils;
import software.amazon.lambda.powertools.logging.Logging;

import java.util.Map;
import java.util.Optional;

public class CreateUrlHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LogManager.getLogger(CreateUrlHandler.class);

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public CreateUrlHandler() {
        this(DynamoDbClient.create(), System.getenv("TABLE_NAME"));
    }

    CreateUrlHandler(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    @Logging(logEvent = true)
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        log.info("Event: {}", input);
        Optional<CreateUrlRequest> request = RequestUtils.parseBody(input.getBody(), CreateUrlRequest.class);
        if (request.isEmpty()) {
            log.error("Request body is empty!");
            return RequestUtils.jsonResponse(400, Map.of("message", "invalid request body"));
        }
        if (StringUtils.isBlank(request.get().longUrl)) {
            log.error("Original URL is empty!");
            return RequestUtils.jsonResponse(400, Map.of("message", "longUrl is required"));
        }

        Optional<Visibility> visibility = Visibility.parse(request.get().visibility);
        if (visibility.isEmpty()) {
            log.error("Visibility: {} is invalid!");
            return RequestUtils.jsonResponse(400, Map.of("message", "visibility must be PUBLIC or PRIVATE"));
        }

        Optional<String> ownerId = AuthUtils.extractOwnerId(input);
        log.info("ownerId: {}", ownerId);
        // Public access cannot create private urls
        if (visibility.get() == Visibility.PRIVATE && ownerId.isEmpty()) {
            log.warn("Rejected create URL request: PRIVATE visibility requires an authenticated caller");
            return RequestUtils.jsonResponse(401, Map.of("message", "unauthorized"));
        }

        return createUrl(request.get().longUrl, ownerId, visibility.get());
    }

    private APIGatewayV2HTTPResponse createUrl(String longUrl, Optional<String> ownerId, Visibility visibility) {
        ShortUrl shortUrl = ShortUrl.create(ShortCodeGenerator.generate(), longUrl, ownerId.orElse(null), visibility);
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(shortUrl.toItem())
                .build());

        log.info("Created shortCode={} visibility={} anonymous={}", shortUrl.shortCode(), visibility, ownerId.isEmpty());
        return RequestUtils.jsonResponse(201, Map.of("shortCode", shortUrl.shortCode(), "visibility", visibility.name()));
    }
}
