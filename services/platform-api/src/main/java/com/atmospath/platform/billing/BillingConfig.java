package com.atmospath.platform.billing;

import com.atmospath.platform.saas.repository.SubscriptionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires all billing beans. Every bean in this configuration is gated on
 * {@code atmospath.billing.enabled=true} so the module is inert until
 * real Lemon Squeezy credentials are provided.
 */
@Configuration
@ConditionalOnProperty(name = "atmospath.billing.enabled", havingValue = "true")
@EnableConfigurationProperties(LemonSqueezyProperties.class)
public class BillingConfig {

    @Bean
    WebhookSignatureVerifier webhookSignatureVerifier(LemonSqueezyProperties properties) {
        return new WebhookSignatureVerifier(properties.webhookSecret());
    }

    @Bean
    LemonSqueezyClient lemonSqueezyClient(LemonSqueezyProperties properties) {
        return new LemonSqueezyClient(properties);
    }

    @Bean
    SubscriptionSyncService subscriptionSyncService(SubscriptionRepository subscriptionRepository,
                                                     LemonSqueezyProperties properties) {
        return new SubscriptionSyncService(subscriptionRepository, properties);
    }

    @Bean
    BillingWebhookController billingWebhookController(WebhookSignatureVerifier verifier,
                                                       SubscriptionSyncService syncService,
                                                       com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new BillingWebhookController(verifier, syncService, objectMapper);
    }

    @Bean
    BillingController billingController(LemonSqueezyClient client,
                                        LemonSqueezyProperties properties,
                                        SubscriptionRepository subscriptionRepository) {
        return new BillingController(client, properties, subscriptionRepository);
    }
}
