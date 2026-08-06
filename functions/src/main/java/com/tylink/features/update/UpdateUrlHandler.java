package com.tylink.features.update;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.tylink.auth.AuthUtils;
import com.tylink.features.update.models.UpdateUrlRequest;
import com.tylink.models.ShortUrl;
import com.tylink.repository.dynamodb.DynamoDbUrlRepository;
import com.tylink.repository.UpdateOutcome;
import com.tylink.repository.UrlRepository;
import com.tylink.repository.UrlRepositoryException;
import com.tylink.utils.LongUrlValidator;
import com.tylink.utils.RequestUtils;
import com.tylink.utils.ShortCodeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.utils.StringUtils;
import software.amazon.lambda.powertools.logging.Logging;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class UpdateUrlHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LogManager.getLogger(UpdateUrlHandler.class);

    private final UrlRepository urlRepository;

    public UpdateUrlHandler() {
        this(new DynamoDbUrlRepository(DynamoDbClient.create(), System.getenv("TABLE_NAME")));
    }

    UpdateUrlHandler(UrlRepository urlRepository) {
        this.urlRepository = urlRepository;
    }

    @Logging(logEvent = true)
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        String shortCode = Optional.ofNullable(input.getPathParameters())
                .map(params -> params.get("shortCode"))
                .orElse(null);
        String ownerId = AuthUtils.extractOwnerIdFromJwtClaims(input);
        log.info("Received update request for shortCode={}", shortCode);

        if (!ShortCodeUtils.isValid(shortCode)) {
            log.warn("Rejected update request: malformed shortCode={}", shortCode);
            return RequestUtils.notFound();
        }

        UpdateUrlRequest request = RequestUtils.parseBody(input.getBody(), UpdateUrlRequest.class);
        if (Objects.isNull(request) || StringUtils.isBlank(request.getLongUrl())) {
            log.error("Request body is empty or missing longUrl");
            return RequestUtils.jsonResponse(400, Map.of("message", "longUrl is required"));
        }
        String longUrl = LongUrlValidator.validate(request.getLongUrl());
        if (Objects.isNull(longUrl)) {
            log.error("Raw URL: {} is invalid!", request.getLongUrl());
            return RequestUtils.jsonResponse(400, Map.of("message", "longUrl must be a valid http or https URL"));
        }

        UpdateOutcome outcome;
        try {
            outcome = urlRepository.updateLongUrl(shortCode, ownerId, longUrl);
        } catch (UrlRepositoryException e) {
            log.error("Failed to update shortCode={} in DynamoDB", shortCode, e);
            return RequestUtils.jsonResponse(500, Map.of("message", "failed to update short url"));
        }

        return switch (outcome.status()) {
            case NOT_FOUND -> {
                log.info("shortCode={} not found or not owned by caller", shortCode);
                yield RequestUtils.notFound();
            }
            case ALREADY_DELETED -> {
                log.info("shortCode={} has been deleted, rejecting update", shortCode);
                yield RequestUtils.jsonResponse(410, toResponseBody(outcome.shortUrl()));
            }
            case UPDATED -> {
                log.info("Updated shortCode={}", shortCode);
                yield RequestUtils.jsonResponse(200, toResponseBody(outcome.shortUrl()));
            }
        };
    }

    private static Map<String, String> toResponseBody(ShortUrl shortUrl) {
        Map<String, String> body = new HashMap<>();
        body.put("shortCode", shortUrl.shortCode());
        body.put("longUrl", shortUrl.longUrl());
        body.put("status", shortUrl.status().name());
        if (shortUrl.updatedAt() != null) {
            body.put("updatedAt", shortUrl.updatedAt());
        }
        if (shortUrl.deletedAt() != null) {
            body.put("deletedAt", shortUrl.deletedAt());
        }
        return body;
    }
}
