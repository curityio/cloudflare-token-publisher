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

package io.curity.identityserver.plugin.events.listeners.config;

import se.curity.identityserver.sdk.config.Configuration;
import se.curity.identityserver.sdk.config.annotation.Description;
import se.curity.identityserver.sdk.service.ExceptionFactory;
import se.curity.identityserver.sdk.service.HttpClient;
import se.curity.identityserver.sdk.service.WebServiceClientFactory;

import java.util.Optional;

@SuppressWarnings("InterfaceNeverImplemented")
public interface CloudflareEventListenerConfiguration extends Configuration
{
    @Description("The Cloudflare account ID")
    String getAccountId();

    @Description("The Cloudflare KV namespace which stores the tokens")
    String getKvNamespace();

    @Description("An API token with permissions to write to the KV store")
    String getAPIToken();

    WebServiceClientFactory getWebServiceClientFactory();

    Optional<HttpClient> getHttpClient();

    ExceptionFactory getExceptionFactory();
}
