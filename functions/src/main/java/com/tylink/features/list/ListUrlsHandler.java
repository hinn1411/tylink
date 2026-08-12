package com.tylink.features.list;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.tylink.auth.AuthUtils;
import com.tylink.features.list.models.ListUrlsResponse;
import com.tylink.features.list.utils.UrlSummaryMapper;
import com.tylink.repository.dynamodb.DynamoDbUrlRepository;
import com.tylink.repository.pagination.InvalidCursorException;
import com.tylink.repository.pagination.UrlPage;
import com.tylink.repository.UrlRepository;
import com.tylink.repository.UrlRepositoryException;
import com.tylink.utils.RequestUtils;
import com.tylink.utils.TracingUtils;
import com.tylink.utils.TylinkResultCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.metrics.FlushMetrics;

import java.util.Map;
import java.util.Optional;

public class ListUrlsHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LogManager.getLogger(ListUrlsHandler.class);
    private static final int DEFAULT_LIMIT = 20;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    private final UrlRepository urlRepository;

    public ListUrlsHandler() {
        this(new DynamoDbUrlRepository(
                DynamoDbClient.builder().overrideConfiguration(TracingUtils.xrayOverrideConfiguration()).build(),
                System.getenv("TABLE_NAME")));
    }

    ListUrlsHandler(UrlRepository urlRepository) {
        this.urlRepository = urlRepository;
    }

    @Logging(logEvent = true)
    @FlushMetrics(captureColdStart = true)
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        String ownerId = AuthUtils.extractOwnerId(input);
        log.info("ownerId: {}", ownerId);

        Map<String, String> queryParams = Optional.ofNullable(input.getQueryStringParameters()).orElse(Map.of());
        String rawLimit = queryParams.get("limit");
        Integer limit = parseLimit(rawLimit);
        if (limit == null) {
            log.warn("Rejected list request: invalid limit={}", rawLimit);
            return RequestUtils.errorResponse(TylinkResultCode.LIST_INVALID_LIMIT);
        }
        String cursor = queryParams.get("cursor");

        UrlPage page;
        try {
            page = urlRepository.listByOwner(ownerId, limit, cursor);
        } catch (InvalidCursorException e) {
            log.warn("Rejected list request: invalid cursor", e);
            return RequestUtils.errorResponse(TylinkResultCode.LIST_INVALID_CURSOR);
        } catch (UrlRepositoryException e) {
            log.error("Failed to list URLs for ownerId={}", ownerId, e);
            return RequestUtils.errorResponse(TylinkResultCode.LIST_FAILED);
        }

        return RequestUtils.successResponse(200,
                new ListUrlsResponse(UrlSummaryMapper.toSummaries(page.items()), page.nextCursor()));
    }

    private static Integer parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(raw.trim());
            return (limit >= MIN_LIMIT && limit <= MAX_LIMIT) ? limit : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
