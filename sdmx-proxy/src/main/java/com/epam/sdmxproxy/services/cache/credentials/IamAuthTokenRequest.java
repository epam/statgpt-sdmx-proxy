package com.epam.sdmxproxy.services.cache.credentials;

import com.amazonaws.DefaultRequest;
import com.amazonaws.Request;
import com.amazonaws.SignableRequest;
import com.amazonaws.auth.AWS4Signer;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.http.HttpMethodName;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Date;
import java.util.stream.Collectors;

/**
 * Generates a presigned IAM authentication token for AWS ElastiCache.
 * The token is a SigV4-signed URL used as the Redis password.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonElastiCache/latest/red-ug/auth-iam.html#auth-iam-Connecting">
 * AWS ElastiCache IAM Authentication</a>
 */
class IamAuthTokenRequest {

    private static final HttpMethodName REQUEST_METHOD = HttpMethodName.GET;
    private static final String REQUEST_PROTOCOL = "http://";
    private static final String PARAM_ACTION = "Action";
    private static final String PARAM_USER = "User";
    private static final String PARAM_RESOURCE_TYPE = "ResourceType";
    private static final String RESOURCE_TYPE_SERVERLESS_CACHE = "ServerlessCache";
    private static final String ACTION_NAME = "connect";
    private static final String SERVICE_NAME = "elasticache";
    private static final long TOKEN_EXPIRY_SECONDS = 900;

    private final String userId;
    private final String clusterName;
    private final String region;
    private final boolean serverless;

    IamAuthTokenRequest(String userId, String clusterName, String region, boolean serverless) {
        this.userId = userId;
        this.clusterName = clusterName;
        this.region = region;
        this.serverless = serverless;
    }

    String toSignedRequestUri(AWSCredentials credentials) throws URISyntaxException {
        Request<Void> request = getSignableRequest();
        sign(request, credentials);

        StringBuilder sb = new StringBuilder();
        sb.append(request.getEndpoint().toString());
        sb.append('?');
        sb.append(request.getParameters().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().get(0))
                .collect(Collectors.joining("&")));

        return sb.toString().replace(REQUEST_PROTOCOL, "");
    }

    private <T> Request<T> getSignableRequest() {
        Request<T> request = new DefaultRequest<>(SERVICE_NAME);
        request.setHttpMethod(REQUEST_METHOD);
        request.setEndpoint(URI.create(REQUEST_PROTOCOL + clusterName + "/"));
        request.addParameters(PARAM_ACTION, Collections.singletonList(ACTION_NAME));
        request.addParameters(PARAM_USER, Collections.singletonList(userId));
        if (serverless) {
            request.addParameters(PARAM_RESOURCE_TYPE, Collections.singletonList(RESOURCE_TYPE_SERVERLESS_CACHE));
        }
        return request;
    }

    private <T> void sign(SignableRequest<T> request, AWSCredentials credentials) {
        AWS4Signer signer = new AWS4Signer();
        signer.setRegionName(region);
        signer.setServiceName(SERVICE_NAME);

        Date expirationDate = new Date(System.currentTimeMillis() + TOKEN_EXPIRY_SECONDS * 1000);
        signer.presignRequest(request, credentials, expirationDate);
    }
}
