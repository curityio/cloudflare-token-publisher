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
import se.curity.identityserver.sdk.service.ExceptionFactory;
import se.curity.identityserver.sdk.service.HttpClient;
import se.curity.identityserver.sdk.service.WebServiceClient;
import se.curity.identityserver.sdk.service.WebServiceClientFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

public final class AccessTokenIssuedListener implements EventListener<IssuedAccessTokenOAuthEvent>
{
    private static final Logger _logger = LoggerFactory.getLogger(AccessTokenIssuedListener.class);
    private static final String CLOUDFLARE_API_URL = "https://api.cloudflare.com/client/v4/";

    private final CloudflareEventListenerConfiguration _configuration;
    private final ExceptionFactory _exceptionFactory;
    private final String _kvNamespacePath;

    public AccessTokenIssuedListener(CloudflareEventListenerConfiguration configuration)
    {
        _configuration = configuration;
        _exceptionFactory = configuration.getExceptionFactory();
        _kvNamespacePath = getPath(configuration.getAccountId(), configuration.getKvNamespace());
    }

    @Override
    public Class<IssuedAccessTokenOAuthEvent> getEventType()
    {
        return IssuedAccessTokenOAuthEvent.class;
    }

    @Override
    public void handle(IssuedAccessTokenOAuthEvent event)
    {
        String accessTokenValue = event.getAccessTokenValue();
        String[] accessTokenParts = accessTokenValue.split("\\.");

        if (accessTokenParts.length != 3)
        {
            _logger.debug("The access token has unexpected format. Expected the token to have 3 parts but found {}.", accessTokenParts.length);
            return;
        }

        String signature = accessTokenParts[2];
        String tokenValue = accessTokenParts[0] + "." + accessTokenParts[1];

        MessageDigest digest;

        try
        {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e)
        {
            _logger.warn("SHA-256 must be available in order to use the Cloudflare event listener");
            throw _exceptionFactory.internalServerException(ErrorCode.GENERIC_ERROR,
                    "SHA-256 must be available in order to use the Cloudflare event listener");
        }

        digest.update(signature.getBytes());
        String hashedSignature = Base64.getEncoder().encodeToString(digest.digest());

        String uri = _kvNamespacePath + hashedSignature + "?expiration=" + event.getExpires().getEpochSecond();

        HttpResponse response = getWebServiceClient(uri)
                .request()
                .header("Authorization", "Bearer " + _configuration.getApiToken())
                .body(HttpRequest.fromString(tokenValue, StandardCharsets.UTF_8))
                .put()
                .response();

        if (response.statusCode() != 200)
        {
            _logger.warn("Event posted to Cloudflare but response was not successful: {}",
                    response.body(HttpResponse.asString()));
        }
        else
        {
            _logger.debug("Successfully sent event to Cloudflare: {}", event);
        }
    }

    private String getPath(String accountId, String kvNamespace)
    {
        return CLOUDFLARE_API_URL + "accounts/" + accountId + "/storage/kv/namespaces/" + kvNamespace + "/values/";
    }

    private WebServiceClient getWebServiceClient(String uri)
    {
        WebServiceClientFactory factory = _configuration.getWebServiceClientFactory();

        Optional<HttpClient> httpClient = _configuration.getHttpClient();
        URI u = URI.create(uri);

        if (httpClient.isPresent())
        {
            HttpClient h = httpClient.get();
            String configuredScheme = h.getScheme();
            String requiredScheme = u.getScheme();

            if (!Objects.equals(configuredScheme, requiredScheme))
            {
                _logger.debug("HTTP client was configured with the scheme {} but {} was expected. Ensure that the " +
                        "configuration is correct.", configuredScheme, requiredScheme);

                throw _exceptionFactory.internalServerException(ErrorCode.CONFIGURATION_ERROR,
                        String.format("HTTP scheme of client is not acceptable; %s is required but %s was found",
                                requiredScheme, configuredScheme));
            }

            return factory.create(h).withHost(u.getHost()).withPath(u.getPath()).withQuery(u.getQuery());
        }
        else
        {
            return factory.create(u).withQuery(u.getQuery());
        }
    }
}