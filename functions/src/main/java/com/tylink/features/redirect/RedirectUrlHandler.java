package com.tylink.features.redirect;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.tylink.auth.AuthUtils;
import com.tylink.models.ShortUrl;
import com.tylink.models.UrlStatus;
import com.tylink.models.Visibility;
import com.tylink.repository.dynamodb.DynamoDbUrlRepository;
import com.tylink.repository.UrlRepository;
import com.tylink.repository.UrlRepositoryException;
import com.tylink.utils.RequestUtils;
import com.tylink.utils.ShortCodeUtils;
import com.tylink.utils.TracingUtils;
import com.tylink.utils.TylinkResultCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.metrics.FlushMetrics;

import java.util.Map;
import java.util.Optional;

public class RedirectUrlHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LogManager.getLogger(RedirectUrlHandler.class);

    private final UrlRepository urlRepository;

    public RedirectUrlHandler() {
        this(new DynamoDbUrlRepository(
                DynamoDbClient.builder().overrideConfiguration(TracingUtils.xrayOverrideConfiguration()).build(),
                System.getenv("TABLE_NAME")));
    }

    RedirectUrlHandler(UrlRepository urlRepository) {
        this.urlRepository = urlRepository;
    }

    @Logging(logEvent = true)
    @FlushMetrics(captureColdStart = true)
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        String shortCode = Optional.ofNullable(input.getPathParameters())
                .map(params -> params.get("shortCode"))
                .orElse(null);
        log.info("Received redirect request for shortCode={}", shortCode);

        if (!ShortCodeUtils.isValid(shortCode)) {
            log.warn("Rejected redirect request: malformed shortCode={}", shortCode);
            return RequestUtils.errorResponse(TylinkResultCode.REDIRECT_NOT_FOUND);
        }

        ShortUrl shortUrl;
        try {
            shortUrl = urlRepository.findByShortCode(shortCode);
        } catch (UrlRepositoryException e) {
            log.error("Failed to read shortCode={} from DynamoDB", shortCode, e);
            return RequestUtils.errorResponse(TylinkResultCode.REDIRECT_LOOKUP_FAILED);
        }

        if (shortUrl == null) {
            log.info("shortCode={} not found", shortCode);
            return RequestUtils.errorResponse(TylinkResultCode.REDIRECT_NOT_FOUND);
        }

        String ownerId = AuthUtils.extractOwnerId(input);
        if (shortUrl.visibility() == Visibility.PRIVATE
                && (ownerId == null || !ownerId.equals(shortUrl.ownerId()))) {
            log.warn("Rejected redirect request: shortCode={} is PRIVATE, caller is not the owner", shortCode);
            return RequestUtils.errorResponse(TylinkResultCode.REDIRECT_NOT_FOUND);
        }

        if (shortUrl.status() == UrlStatus.DELETED) {
            log.info("shortCode={} has been deleted", shortCode);
            return RequestUtils.errorResponse(TylinkResultCode.REDIRECT_GONE);
        }

        log.info("Redirecting shortCode={}", shortCode);
        RequestUtils.recordSuccess(TylinkResultCode.SUCCESS);
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(307)
                .withHeaders(Map.of("Location", shortUrl.longUrl()))
                .withBody("")
                .build();
    }
}
