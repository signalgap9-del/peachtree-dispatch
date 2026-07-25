package com.atmospath.platform.risk;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Subscribes the SSE layer to Redis {@code alerts:*} channels when
 * {@code atmospath.alert-stream.mode=pubsub}. Alert state changes published
 * by the SaaS stack are broadcast to connected clients without polling the
 * risk engine. The container is started/stopped with the application
 * context; when Redis is unavailable the listener retries in the background
 * and heartbeats keep SSE connections alive in the meantime.
 */
@Component
@ConditionalOnProperty(name = "atmospath.alert-stream.mode", havingValue = "pubsub")
public class AlertStreamPubSubBridge implements SmartLifecycle {

    static final String CHANNEL_PATTERN = "alerts:*";

    private static final Logger log = LoggerFactory.getLogger(AlertStreamPubSubBridge.class);

    private final RedisConnectionFactory connectionFactory;
    private final AlertStreamService alertStreamService;
    private final ObjectMapper objectMapper;
    private final RedisMessageListenerContainer container;

    private volatile boolean running;

    public AlertStreamPubSubBridge(RedisConnectionFactory connectionFactory,
                                   AlertStreamService alertStreamService,
                                   ObjectMapper objectMapper) {
        this.connectionFactory = connectionFactory;
        this.alertStreamService = alertStreamService;
        this.objectMapper = objectMapper;
        this.container = new RedisMessageListenerContainer();
        this.container.setConnectionFactory(connectionFactory);
    }

    @Override
    public void start() {
        MessageListener listener = (message, pattern) -> {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            String tenantId = channel.startsWith("alerts:") ? channel.substring("alerts:".length()) : channel;
            try {
                JsonNode payload = objectMapper.readTree(body);
                alertStreamService.broadcastTenantAlert(tenantId, payload);
            } catch (Exception ex) {
                log.warn("Dropping unparseable alert payload on channel {}: {}", channel, ex.toString());
            }
        };
        container.addMessageListener(listener, new PatternTopic(CHANNEL_PATTERN));
        container.start();
        running = true;
        log.info("Alert stream subscribed to Redis channel pattern '{}'", CHANNEL_PATTERN);
    }

    @Override
    public void stop() {
        try {
            container.stop();
        } catch (RuntimeException ex) {
            log.warn("Error stopping alert stream Redis subscription: {}", ex.toString());
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
