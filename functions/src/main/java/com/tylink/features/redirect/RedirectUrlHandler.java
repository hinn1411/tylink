package com.tylink.features.redirect;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.tylink.auth.AuthUtils;
import com.tylink.model.ShortUrl;
import com.tylink.model.UrlStatus;
import com.tylink.model.Visibility;
import com.tylink.repository.DynamoDbUrlRepository;
import com.tylink.repository.UrlRepository;
import com.tylink.repository.UrlRepositoryException;
import com.tylink.util.RequestUtils;
import com.tylink.util.ShortCodeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.lambda.powertools.logging.Logging;

import java.util.Map;
import java.util.Optional;

public class RedirectUrlHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LogManager.getLogger(RedirectUrlHandler.class);
    private static final Map<String, String> NOT_FOUND_BODY = Map.of("message", "Short url not found");

    private final UrlRepository urlRepository;

    public RedirectUrlHandler() {
        this(new DynamoDbUrlRepository(DynamoDbClient.create(), System.getenv("TABLE_NAME")));
    }

    RedirectUrlHandler(UrlRepository urlRepository) {
        this.urlRepository = urlRepository;
    }

    @Logging(logEvent = true)
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        String shortCode = Optional.ofNullable(input.getPathParameters())
                .map(params -> params.get("shortCode"))
                .orElse(null);
        log.info("Received redirect request for shortCode={}", shortCode);

        if (!ShortCodeUtils.isValid(shortCode)) {
            log.warn("Rejected redirect request: malformed shortCode={}", shortCode);
            return notFound();
        }

        ShortUrl shortUrl;
        try {
            shortUrl = urlRepository.findByShortCode(shortCode);
        } catch (UrlRepositoryException e) {
            log.error("Failed to read shortCode={} from DynamoDB", shortCode, e);
            return RequestUtils.jsonResponse(500, Map.of("message", "failed to look up short url"));
        }

        if (shortUrl == null) {
            log.info("shortCode={} not found", shortCode);
            return notFound();
        }

        String ownerId = AuthUtils.extractOwnerId(input);
        if (shortUrl.visibility() == Visibility.PRIVATE
                && (ownerId == null || !ownerId.equals(shortUrl.ownerId()))) {
            log.warn("Rejected redirect request: shortCode={} is PRIVATE, caller is not the owner", shortCode);
            return notFound();
        }

        if (shortUrl.status() == UrlStatus.DELETED) {
            log.info("shortCode={} has been deleted", shortCode);
            return RequestUtils.jsonResponse(410, Map.of("message", "short url no longer available"));
        }

        log.info("Redirecting shortCode={}", shortCode);
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(307)
                .withHeaders(Map.of("Location", shortUrl.longUrl()))
                .withBody("")
                .build();
    }

    private static APIGatewayV2HTTPResponse notFound() {
        return RequestUtils.jsonResponse(404, NOT_FOUND_BODY);
    }
}
