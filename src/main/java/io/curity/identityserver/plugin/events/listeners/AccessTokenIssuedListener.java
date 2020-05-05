/*
 *  Copyright 2020 Curity AB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.curity.identityserver.plugin.events.listeners;

import io.curity.identityserver.plugin.events.listeners.config.CloudflareEventListenerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.curity.identityserver.sdk.data.events.IssuedAccessTokenOAuthEvent;
import se.curity.identityserver.sdk.errors.ErrorCode;
import se.curity.identityserver.sdk.event.EventListener;
import se.curity.identityserver.sdk.http.HttpRequest;
import se.curity.identityserver.sdk.http.HttpResponse;
import se.curity.identityserver.sdk.service.HttpClient;
import se.curity.identityserver.sdk.service.WebServiceClient;
import se.curity.identityserver.sdk.service.WebServiceClientFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Optional;

public class AccessTokenIssuedListener implements EventListener<IssuedAccessTokenOAuthEvent>
{
    private static final Logger _logger = LoggerFactory.getLogger(AccessTokenIssuedListener.class);
    private static final String CLOUDFLARE_API_URL = "https://api.cloudflare.com/client/v4/";

    private final CloudflareEventListenerConfiguration configuration;

    public AccessTokenIssuedListener(CloudflareEventListenerConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public Class<IssuedAccessTokenOAuthEvent> getEventType()
    {
        return IssuedAccessTokenOAuthEvent.class;
    }

    @Override
    public void handle(IssuedAccessTokenOAuthEvent event) {
        String accessTokenValue = event.getAccessTokenValue();
        String[] accessTokenParts = accessTokenValue.split("\\.");

        String signature = accessTokenParts[2];
        String tokenValue = accessTokenParts[0] + "." + accessTokenParts[1];

        MessageDigest digest;

        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            _logger.warn("MD5 should be available to use the cloudflare event listener");
            return;
        }

        digest.update(signature.getBytes());
        String hashedSignature = byteArray2Hex(digest.digest());

        String uri = CLOUDFLARE_API_URL + getPath(configuration, hashedSignature) + "?expiration=" + event.getExpires().getEpochSecond();

        HttpResponse response = getWebServiceClient(uri)
                .request()
                .header("Authorization", "Bearer " + configuration.getAPIToken())
                .body(HttpRequest.fromString(tokenValue, StandardCharsets.UTF_8))
                .put()
                .response();

        if (response.statusCode() != 200) {
            _logger.warn("Event posted to Cloudflare but response was not successful: {}",
                    response.body(HttpResponse.asString()));
        } else {
            _logger.debug("Successfully sent event to Cloudflare: {}", event);
        }
    }

    private String getPath(CloudflareEventListenerConfiguration configuration, String hashedSignature) {
        return "accounts/" + configuration.getAccountId() + "/storage/kv/namespaces/" + configuration.getKvNamespace() + "/values/" + hashedSignature;
    }

    private static final char[] hex = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

    private static String byteArray2Hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for(final byte b : bytes) {
            sb.append(hex[(b & 0xF0) >> 4]);
            sb.append(hex[b & 0x0F]);
        }
        return sb.toString();
    }

    private WebServiceClient getWebServiceClient(String uri) {
        WebServiceClientFactory factory = configuration.getWebServiceClientFactory();

        Optional<HttpClient> httpClient = configuration.getHttpClient();
        URI u = URI.create(uri);

        if (httpClient.isPresent()) {
            HttpClient h = httpClient.get();
            String configuredScheme = h.getScheme();
            String requiredScheme = u.getScheme();

            if (!Objects.equals(configuredScheme, requiredScheme)) {
                _logger.debug("HTTP client was configured with the scheme {} but {} was expected. Ensure that the " +
                        "configuration is correct.", configuredScheme, requiredScheme);

                throw configuration.getExceptionFactory().internalServerException(ErrorCode.CONFIGURATION_ERROR,
                        String.format("HTTP scheme of client is not acceptable; %s is required but %s was found",
                                requiredScheme, configuredScheme));
            }

            return factory.create(h).withHost(u.getHost()).withPath(u.getPath());
        } else {
            return factory.create(u);
        }
    }
}