/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.oauth2;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.gravitee.policy.oauth2.DummyOAuth2Resource.CLIENT_ID;
import static java.util.concurrent.TimeUnit.HOURS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.apim.gateway.tests.sdk.AbstractPolicyTest;
import io.gravitee.apim.gateway.tests.sdk.annotations.DeployApi;
import io.gravitee.apim.gateway.tests.sdk.annotations.GatewayTest;
import io.gravitee.apim.gateway.tests.sdk.configuration.GatewayConfigurationBuilder;
import io.gravitee.apim.gateway.tests.sdk.resource.ResourceBuilder;
import io.gravitee.definition.model.Api;
import io.gravitee.definition.model.ExecutionMode;
import io.gravitee.definition.model.Plan;
import io.gravitee.gateway.api.service.Subscription;
import io.gravitee.gateway.api.service.SubscriptionService;
import io.gravitee.gateway.reactive.api.policy.SecurityToken;
import io.gravitee.plugin.resource.ResourcePlugin;
import io.gravitee.policy.oauth2.configuration.OAuth2PolicyConfiguration;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.http.HttpMethod;
import io.vertx.rxjava3.core.buffer.Buffer;
import io.vertx.rxjava3.core.http.HttpClient;
import io.vertx.rxjava3.core.http.HttpClientRequest;
import io.vertx.rxjava3.core.http.HttpClientResponse;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.OngoingStubbing;

/**
 * @author GraviteeSource Team
 */
@GatewayTest
@DeployApi("/apis/oauth2.json")
public class Oauth2PolicyIntegrationTest extends AbstractPolicyTest<Oauth2Policy, OAuth2PolicyConfiguration> {

    public static final String API_ID = "my-api";
    public static final String PLAN_ID = "plan-id";

    @Override
    protected void configureGateway(GatewayConfigurationBuilder gatewayConfigurationBuilder) {
        super.configureGateway(gatewayConfigurationBuilder);
        gatewayConfigurationBuilder.set("api.jupiterMode.enabled", "true");
    }

    @Override
    public void configureResources(Map<String, ResourcePlugin> resources) {
        resources.put("dummy-oauth2-resource", ResourceBuilder.build("dummy-oauth2-resource", DummyOAuth2Resource.class));
    }

    /**
     * Override api plans to have a published JWT one.
     * @param api is the api to apply this function code
     */
    @Override
    public void configureApi(Api api) {
        Plan oauth2Plan = new Plan();
        oauth2Plan.setId(PLAN_ID);
        oauth2Plan.setApi(api.getId());
        oauth2Plan.setSecurity("OAUTH2");
        oauth2Plan.setStatus("PUBLISHED");

        OAuth2PolicyConfiguration configuration = new OAuth2PolicyConfiguration();
        configuration.setOauthResource("dummy-oauth2-resource");
        try {
            oauth2Plan.setSecurityDefinition(new ObjectMapper().writeValueAsString(configuration));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to set OAuth2 policy configuration", e);
        }

        api.setPlans(Collections.singletonList(oauth2Plan));
        api.setExecutionMode(ExecutionMode.JUPITER);
    }

    @Test
    @DisplayName("Should receive 401 - Unauthorized when calling without any Authorization Header")
    void shouldGet401_ifNoToken(HttpClient client) throws InterruptedException {
        wiremock.stubFor(get("/team").willReturn(ok("response from backend")));

        Single<HttpClientResponse> httpClientResponse = client.rxRequest(HttpMethod.GET, "/test").flatMap(HttpClientRequest::rxSend);

        assert401unauthorized(httpClientResponse);
    }

    @Test
    @DisplayName("Should receive 401 - Unauthorized when calling with a wrong Authorization Header")
    void shouldGet401_ifWrongToken(HttpClient client) throws InterruptedException {
        wiremock.stubFor(get("/team").willReturn(ok("response from backend")));

        Single<HttpClientResponse> httpClientResponse = client
            .rxRequest(HttpMethod.GET, "/test")
            .flatMap(request -> request.putHeader("Authorization", "Bearer " + DummyOAuth2Resource.TOKEN_FAIL).rxSend());

        assert401unauthorized(httpClientResponse);
    }

    @Test
    @DisplayName("Should receive 401 - Unauthorized when calling with an token, which introspection returns an invalid payload")
    void shouldGet401_ifInvalidIntrospectionPayload(HttpClient client) throws InterruptedException {
        wiremock.stubFor(get("/team").willReturn(ok("response from backend")));

        Single<HttpClientResponse> httpClientResponse = client
            .rxRequest(HttpMethod.GET, "/test")
            .flatMap(
                request -> request.putHeader("Authorization", "Bearer " + DummyOAuth2Resource.TOKEN_SUCCESS_WITH_INVALID_PAYLOAD).rxSend()
            );

        verifyNoInteractions(getBean(SubscriptionService.class));
        assert401unauthorized(httpClientResponse);
    }

    @Test
    @DisplayName("Should receive 401 - Unauthorized when calling with an valid token, but introspection return no client_id")
    void shouldGet401_ifNoClientId(HttpClient client) throws InterruptedException {
        wiremock.stubFor(get("/team").willReturn(ok("response from backend")));

        Single<HttpClientResponse> httpClientResponse = client
            .rxRequest(HttpMethod.GET, "/test")
            .flatMap(
                request -> request.putHeader("Authorization", "Bearer " + DummyOAuth2Resource.TOKEN_SUCCESS_WITHOUT_CLIENT_ID).rxSend()
            );

        verifyNoInteractions(getBean(SubscriptionService.class));
        assert401unauthorized(httpClientResponse);
    }

    @Test
    @DisplayName("Should receive 401 - Unauthorized when calling with an valid token, but no subscription found")
    void shouldGet401_ifSubscriptionNotFound(HttpClient client) throws InterruptedException {
        wiremock.stubFor(get("/team").willReturn(ok("response from backend")));

        // no subscription found
        whenSearchingSubscription(API_ID, CLIENT_ID, PLAN_ID).thenReturn(Optional.empty());

        Single<HttpClientResponse> httpClientResponse = client
            .rxRequest(HttpMethod.GET, "/test")
            .flatMap(request -> request.putHeader("Authorization", "Bearer " + DummyOAuth2Resource.TOKEN_SUCCESS_WITH_CLIENT_ID).rxSend());

        assert401unauthorized(httpClientResponse);
    }

    @Test
    @DisplayName("Should receive 401 - Unauthorized when calling with an valid token, but subscription is expired")
    void shouldGet401_ifSubscriptionExpired(HttpClient client) throws InterruptedException {
        wiremock.stubFor(get("/team").willReturn(ok("response from backend")));

        // subscription found is expired
        whenSearchingSubscription(API_ID, CLIENT_ID, PLAN_ID).thenReturn(Optional.of(fakeSubscriptionFromCache(true)));

        Single<HttpClientResponse> httpClientResponse = client
            .rxRequest(HttpMethod.GET, "/test")
            .flatMap(request -> request.putHeader("Authorization", "Bearer " + DummyOAuth2Resource.TOKEN_SUCCESS_WITH_CLIENT_ID).rxSend());

        assert401unauthorized(httpClientResponse);
    }

    @Test
    @DisplayName("Should access API with correct Authorization header and a valid subscription")
    void shouldAccessApiWithValidTokenAndSubscription(HttpClient client) throws InterruptedException {
        wiremock.stubFor(get("/team").willReturn(ok("response from backend")));

        // subscription found is valid
        whenSearchingSubscription(API_ID, CLIENT_ID, PLAN_ID).thenReturn(Optional.of(fakeSubscriptionFromCache(false)));

        client
            .rxRequest(HttpMethod.GET, "/test")
            .flatMap(request -> request.putHeader("Authorization", "Bearer " + DummyOAuth2Resource.TOKEN_SUCCESS_WITH_CLIENT_ID).rxSend())
            .flatMapPublisher(
                response -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    return response.toFlowable();
                }
            )
            .test()
            .await()
            .assertComplete()
            .assertValue(
                body -> {
                    assertThat(body.toString()).isEqualTo("response from backend");
                    return true;
                }
            )
            .assertNoErrors();

        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/team")));
    }

    private Subscription fakeSubscriptionFromCache(boolean isExpired) {
        final Subscription subscription = new Subscription();
        subscription.setApplication("application-id");
        subscription.setId("subscription-id");
        subscription.setPlan(PLAN_ID);
        if (isExpired) {
            subscription.setEndingAt(new Date(Instant.now().minus(1, HOURS.toChronoUnit()).toEpochMilli()));
        }
        return subscription;
    }

    private void assert401unauthorized(Single<HttpClientResponse> httpClientResponse) throws InterruptedException {
        httpClientResponse
            .flatMapPublisher(
                response -> {
                    assertThat(response.statusCode()).isEqualTo(401);
                    return response.body().toFlowable();
                }
            )
            .test()
            .await()
            .assertComplete()
            .assertValue(
                body -> {
                    assertUnauthorizedResponseBody(body.toString());
                    return true;
                }
            )
            .assertNoErrors();

        wiremock.verify(0, getRequestedFor(urlPathEqualTo("/team")));
    }

    protected OngoingStubbing<Optional<Subscription>> whenSearchingSubscription(String api, String clientId, String plan) {
        return when(getBean(SubscriptionService.class).getByApiAndSecurityToken(eq(api), securityTokenMatcher(clientId), eq(plan)));
    }

    protected void assertUnauthorizedResponseBody(String responseBody) {
        assertThat(responseBody).isEqualTo("Unauthorized");
    }

    private SecurityToken securityTokenMatcher(String clientId) {
        return argThat(
            securityToken ->
                securityToken.getTokenType().equals(SecurityToken.TokenType.CLIENT_ID.name()) &&
                securityToken.getTokenValue().equals(clientId)
        );
    }
}
