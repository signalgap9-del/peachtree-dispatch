package com.atmospath.platform.llm.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.atmospath.platform.llm.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads YAML prompt templates from the classpath {@code prompts/} directory
 * at startup, caches them in memory, and renders them by substituting
 * {@code {variable}} placeholders. Each template declares a {@code version}
 * field so future A/B experiments can load a specific revision by name.
 */
@Service
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);
    private static final String TEMPLATE_LOCATION_PATTERN = "classpath:prompts/*.yaml";
    private static final Pattern VARIABLE = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_]*)\\}");

    private final Map<String, PromptTemplate> templatesByName = new ConcurrentHashMap<>();

    public PromptTemplateService() {
        loadTemplates();
    }

    /** A parsed prompt template: versioned system and user message bodies. */
    public record PromptTemplate(int version, String name, String description, String system, String user) {
    }

    public Set<String> templateNames() {
        return Set.copyOf(templatesByName.keySet());
    }

    public PromptTemplate template(String templateName) {
        return requireTemplate(templateName);
    }

    /**
     * Renders the template's user body with the given variables. Required
     * variables are collected from both the system and user bodies, so a
     * missing system variable (for example {@code schema}) fails fast here
     * too.
     */
    public String render(String templateName, Map<String, String> variables) {
        PromptTemplate template = requireTemplate(templateName);
        validateVariables(template, variables);
        return substitute(template.user(), variables);
    }

    /** Builds the chat messages (system then user) with variables substituted. */
    public List<Message> buildMessages(String templateName, Map<String, String> variables) {
        PromptTemplate template = requireTemplate(templateName);
        validateVariables(template, variables);
        return List.of(
                Message.system(substitute(template.system(), variables)),
                Message.user(substitute(template.user(), variables)));
    }

    private PromptTemplate requireTemplate(String templateName) {
        PromptTemplate template = templatesByName.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Unknown prompt template: " + templateName);
        }
        return template;
    }

    private void validateVariables(PromptTemplate template, Map<String, String> variables) {
        Set<String> required = requiredVariables(template);
        required.removeAll(variables.keySet());
        if (!required.isEmpty()) {
            throw new IllegalArgumentException(
                    "Template '" + template.name() + "' is missing required variables: " + required);
        }
    }

    private static Set<String> requiredVariables(PromptTemplate template) {
        Set<String> variables = new LinkedHashSet<>();
        collectVariables(template.system(), variables);
        collectVariables(template.user(), variables);
        return variables;
    }

    private static void collectVariables(String text, Set<String> sink) {
        Matcher matcher = VARIABLE.matcher(text);
        while (matcher.find()) {
            sink.add(matcher.group(1));
        }
    }

    private static String substitute(String text, Map<String, String> variables) {
        Matcher matcher = VARIABLE.matcher(text);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = variables.getOrDefault(matcher.group(1), matcher.group());
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private void loadTemplates() {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(TEMPLATE_LOCATION_PATTERN);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not scan for prompt templates", ex);
        }
        Yaml yaml = new Yaml();
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                Map<String, Object> document = yaml.load(in);
                PromptTemplate template = toTemplate(document, resource.getFilename());
                templatesByName.put(template.name(), template);
            } catch (IOException ex) {
                throw new IllegalStateException("Could not read prompt template " + resource.getFilename(), ex);
            }
        }
        log.info("Loaded {} prompt template(s): {}", templatesByName.size(), templatesByName.keySet());
    }

    private static PromptTemplate toTemplate(Map<String, Object> document, String fileName) {
        if (document == null) {
            throw new IllegalStateException("Prompt template " + fileName + " is empty");
        }
        String name = requiredField(document, "name", fileName);
        String system = requiredField(document, "system", fileName);
        String user = requiredField(document, "user", fileName);
        Object version = document.getOrDefault("version", 1);
        String description = String.valueOf(document.getOrDefault("description", ""));
        return new PromptTemplate(((Number) version).intValue(), name, description, system, user);
    }

    private static String requiredField(Map<String, Object> document, String field, String fileName) {
        Object value = document.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("Prompt template " + fileName + " is missing field '" + field + "'");
        }
        return String.valueOf(value);
    }
}
