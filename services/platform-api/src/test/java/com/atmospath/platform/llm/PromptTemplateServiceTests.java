package com.atmospath.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.atmospath.platform.llm.prompt.PromptTemplateService;
import org.junit.jupiter.api.Test;

class PromptTemplateServiceTests {

    private final PromptTemplateService service = new PromptTemplateService();

    @Test
    void loadsAllBundledTemplatesFromClasspath() {
        assertThat(service.templateNames()).containsExactlyInAnyOrder(
                "intent_classification",
                "nl2opt_extraction",
                "route_explanation",
                "alert_summary",
                "comparison_report");
    }

    @Test
    void renderSubstitutesVariablesInUserBody() {
        String rendered = service.render("intent_classification", Map.of("message", "Plan a route to Tulsa"));

        assertThat(rendered).contains("Plan a route to Tulsa");
        assertThat(rendered).doesNotContain("{message}");
    }

    @Test
    void renderFailsWhenRequiredVariableIsMissing() {
        assertThatThrownBy(() -> service.render("intent_classification", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message");
    }

    @Test
    void systemVariablesAreRequiredEvenWhenRenderingUserBody() {
        assertThatThrownBy(() -> service.render("nl2opt_extraction", Map.of("message", "Drive to Waco")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void buildMessagesReturnsRenderedSystemThenUser() {
        List<Message> messages = service.buildMessages(
                "nl2opt_extraction",
                Map.of("schema", "{\"type\":\"object\"}", "message", "Deliver to Waco by 10:00"));

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo(Message.Role.SYSTEM);
        assertThat(messages.get(0).content()).contains("{\"type\":\"object\"}");
        assertThat(messages.get(1).role()).isEqualTo(Message.Role.USER);
        assertThat(messages.get(1).content()).contains("Deliver to Waco by 10:00");
    }

    @Test
    void unknownTemplateThrows() {
        assertThatThrownBy(() -> service.render("does_not_exist", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does_not_exist");
    }

    @Test
    void templateExposesVersionForFutureAbTesting() {
        PromptTemplateService.PromptTemplate template = service.template("intent_classification");

        assertThat(template.version()).isEqualTo(1);
        assertThat(template.name()).isEqualTo("intent_classification");
        assertThat(template.description()).isNotBlank();
    }
}
