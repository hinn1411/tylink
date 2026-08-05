package com.tylink.features.delete;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.tylink.auth.AuthUtils;
import com.tylink.repository.dynamodb.DynamoDbUrlRepository;
import com.tylink.repository.UrlRepository;
import com.tylink.repository.UrlRepositoryException;
import com.tylink.utils.RequestUtils;
import com.tylink.utils.ShortCodeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.lambda.powertools.logging.Logging;

import java.util.Map;
import java.util.Optional;

public class DeleteUrlHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LogManager.getLogger(DeleteUrlHandler.class);

    private final UrlRepository urlRepository;

    public DeleteUrlHandler() {
        this(new DynamoDbUrlRepository(DynamoDbClient.create(), System.getenv("TABLE_NAME")));
    }

    DeleteUrlHandler(UrlRepository urlRepository) {
        this.urlRepository = urlRepository;
    }

    @Logging(logEvent = true)
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        String shortCode = Optional.ofNullable(input.getPathParameters())
                .map(params -> params.get("shortCode"))
                .orElse(null);
        String ownerId = AuthUtils.extractOwnerIdFromJwtClaims(input);
        log.info("Received delete request for shortCode={}", shortCode);

        if (!ShortCodeUtils.isValid(shortCode)) {
            log.warn("Rejected delete request: malformed shortCode={}", shortCode);
            return RequestUtils.notFound();
        }

        boolean deleted;
        try {
            deleted = urlRepository.markDeleted(shortCode, ownerId);
        } catch (UrlRepositoryException e) {
            log.error("Failed to delete shortCode={} in DynamoDB", shortCode, e);
            return RequestUtils.jsonResponse(500, Map.of("message", "failed to delete short url"));
        }

        if (!deleted) {
            log.info("shortCode={} not found or not owned by caller", shortCode);
            return RequestUtils.notFound();
        }

        log.info("Deleted shortCode={}", shortCode);
        return RequestUtils.jsonResponse(410, Map.of("message", "short url deleted"));
    }
}
