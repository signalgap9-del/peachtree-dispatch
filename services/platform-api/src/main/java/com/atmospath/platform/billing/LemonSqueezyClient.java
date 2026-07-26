package com.atmospath.platform.billing;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Thin REST client for the Lemon Squeezy API. All methods return parsed
 * response bodies as {@link Map} so callers can extract what they need
 * without coupling to a rigid DTO hierarchy.
 */
public class LemonSqueezyClient {

    private final RestClient restClient;

    public LemonSqueezyClient(LemonSqueezyProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.apiBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** Constructor for testing with a pre-built RestClient. */
    LemonSqueezyClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Creates a Lemon Squeezy checkout and returns the full response body.
     *
     * @param storeId   the store ID
     * @param variantId the variant ID
     * @param customData arbitrary key-value pairs passed through the webhook
     * @return parsed JSON response containing {@code data.attributes.url}
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createCheckout(String storeId, String variantId,
                                               Map<String, String> customData) {
        Map<String, Object> body = Map.of(
                "data", Map.of(
                        "type", "checkouts",
                        "attributes", Map.of(
                                "checkout_data", Map.of("custom", customData)),
                        "relationships", Map.of(
                                "store", Map.of(
                                        "data", Map.of("type", "stores", "id", storeId)),
                                "variant", Map.of(
                                        "data", Map.of("type", "variants", "id", variantId)))));
        return restClient.post()
                .uri("/checkouts")
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    /** Fetches a single subscription by its Lemon Squeezy ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSubscription(String subscriptionId) {
        return restClient.get()
                .uri("/subscriptions/{id}", subscriptionId)
                .retrieve()
                .body(Map.class);
    }

    /** Lists subscriptions, optionally filtered by store ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> listSubscriptions(String storeId) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/subscriptions")
                        .queryParam("filter[store_id]", storeId)
                        .build())
                .retrieve()
                .body(Map.class);
    }
}
