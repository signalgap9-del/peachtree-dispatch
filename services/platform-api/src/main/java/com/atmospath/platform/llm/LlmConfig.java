package com.atmospath.platform.llm;

import java.time.Duration;

import com.atmospath.platform.llm.nl2opt.VrpConstraintValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the LLM subsystem. Every bean here is conditional on
 * {@code atmospath.llm.enabled=true} so the rest of the application
 * is completely unaffected when LLM support is off.
 */
@Configuration
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    RestClient llmRestClient(LlmProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.min(props.timeoutMs(), 10_000L)));
        factory.setReadTimeout(Duration.ofMillis(props.timeoutMs()));
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Bean
    LiteLlmClient llmClient(LlmProperties props, RestClient llmRestClient, ObjectMapper objectMapper) {
        return new LiteLlmClient(props, llmRestClient, objectMapper);
    }

    @Bean
    LlmStreamService llmStreamService(LiteLlmClient llmClient, LlmProperties props) {
        return new LlmStreamService(llmClient, props);
    }

    @Bean
    LlmTokenBudgetService llmTokenBudgetService(StringRedisTemplate redis, LlmProperties props) {
        return new LlmTokenBudgetService(redis, props);
    }

    @Bean
    VrpConstraintValidator vrpConstraintValidator() {
        return new VrpConstraintValidator();
    }
}
