package com.atmospath.platform.llm.intent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.atmospath.platform.llm.LlmClient;
import com.atmospath.platform.llm.Message;
import com.atmospath.platform.llm.prompt.PromptTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Classifies a user message into a routing {@link Intent}. The primary path
 * calls the LLM with the {@code intent_classification} prompt template; when
 * the LLM is unavailable the service degrades to a deterministic keyword
 * heuristic so routing keeps working.
 */
@Service
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class IntentClassificationService {

    private static final Logger log = LoggerFactory.getLogger(IntentClassificationService.class);
    private static final String TEMPLATE_NAME = "intent_classification";
    private static final int LOGGED_MESSAGE_LIMIT = 80;
    private static final Pattern FROM_TO = Pattern.compile("\\bfrom\\b.+\\bto\\b");

    private final LlmClient llmClient;
    private final PromptTemplateService templates;

    public IntentClassificationService(LlmClient llmClient, PromptTemplateService templates) {
        this.llmClient = llmClient;
        this.templates = templates;
    }

    public Intent classify(String userMessage) {
        try {
            List<Message> messages = templates.buildMessages(TEMPLATE_NAME, Map.of("message", userMessage));
            String raw = llmClient.complete(messages);
            Intent intent = Intent.fromLlmOutput(raw);
            log.info("Intent classified via LLM: intent={} confidence=high message='{}'",
                    intent, truncate(userMessage));
            return intent;
        } catch (RuntimeException ex) {
            log.warn("LLM intent classification failed; using keyword fallback: {}", ex.toString());
            Intent intent = classifyByKeywords(userMessage);
            log.info("Intent classified via keyword fallback: intent={} confidence=low message='{}'",
                    intent, truncate(userMessage));
            return intent;
        }
    }

    Intent classifyByKeywords(String userMessage) {
        String text = userMessage.toLowerCase(Locale.ROOT);
        if (text.contains("route") || text.contains("directions") || FROM_TO.matcher(text).find()) {
            return Intent.ROUTE_PLAN;
        }
        if (text.contains("change") || text.contains("modify") || text.contains("what if")) {
            return Intent.MODIFY;
        }
        if (text.contains("compare") || text.contains("which is better")) {
            return Intent.COMPARE;
        }
        if (text.contains("deliver") || text.contains("fleet") || text.contains("driver")) {
            return Intent.FLEET_OPTIMIZE;
        }
        return Intent.EXPLAIN;
    }

    private static String truncate(String message) {
        if (message.length() <= LOGGED_MESSAGE_LIMIT) {
            return message;
        }
        return message.substring(0, LOGGED_MESSAGE_LIMIT) + "...";
    }
}
