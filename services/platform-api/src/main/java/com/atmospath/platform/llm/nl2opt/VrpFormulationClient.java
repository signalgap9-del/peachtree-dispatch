package com.atmospath.platform.llm.nl2opt;

import java.util.List;
import java.util.Map;

import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.VrpConstraints;
import com.atmospath.platform.risk.RiskEngineGateway;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Spring client that bridges LLM-extracted VRP constraints to the FastAPI
 * {@code /nl2opt/solve} endpoint. The risk engine (Python) handles
 * formulation and OR-Tools solving; this client serialises the constraint
 * records, POSTs them, and deserialises the solution.
 */
@Component
public class VrpFormulationClient {

    private static final Logger log = LoggerFactory.getLogger(VrpFormulationClient.class);
    private static final String SOLVE_PATH = "/nl2opt/solve";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RiskEngineGateway riskEngine;

    public VrpFormulationClient(RiskEngineGateway riskEngine) {
        this.riskEngine = riskEngine;
    }

    /**
     * Sends validated constraints to the risk engine for formulation and
     * solving. Returns a {@link SolutionResult} with routes, metadata, and
     * human-readable explanations suitable for LLM interpretation.
     */
    public SolutionResult solve(VrpConstraints constraints) {
        try {
            JsonNode body = MAPPER.createObjectNode()
                    .set("constraints", MAPPER.valueToTree(constraints));
            JsonNode response = riskEngine.post(SOLVE_PATH, body);
            return parseResponse(response);
        } catch (Exception ex) {
            log.error("nl2opt solve call to risk engine failed", ex);
            return new SolutionResult(List.of(), Map.of(), "ERROR",
                    List.of("Risk engine call failed: " + ex.getMessage()));
        }
    }

    private SolutionResult parseResponse(JsonNode response) {
        if (response == null) {
            return new SolutionResult(List.of(), Map.of(), "ERROR",
                    List.of("Empty response from risk engine"));
        }

        String solverStatus = response.path("solver_status").asText("UNKNOWN");

        List<RouteResult> routes = List.of();
        JsonNode routesNode = response.path("routes");
        if (routesNode.isArray()) {
            routes = MAPPER.convertValue(routesNode,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, RouteResult.class));
        }

        Map<String, Object> metadata = Map.of();
        JsonNode formulationNode = response.path("formulation");
        if (formulationNode.isObject()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = MAPPER.convertValue(formulationNode, Map.class);
            metadata = parsed;
        }

        List<String> explanations = List.of();
        JsonNode explanationsNode = response.path("explanations");
        if (explanationsNode.isArray()) {
            explanations = MAPPER.convertValue(explanationsNode,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        }

        return new SolutionResult(routes, metadata, solverStatus, explanations);
    }

    /** A single vehicle route from the solver. */
    public record RouteResult(String vehicleId, List<StopResult> stops) {
    }

    /** A stop within a route. */
    public record StopResult(String nodeId, String label, int sequence) {
    }

    /**
     * Complete result of an nl2opt solve call.
     *
     * @param routes              solved vehicle routes
     * @param formulationMetadata formulation details (node count, matrix source, etc.)
     * @param solverStatus        FEASIBLE, INFEASIBLE, or ERROR
     * @param explanations        human-readable constraint translation notes
     */
    public record SolutionResult(
            List<RouteResult> routes,
            Map<String, Object> formulationMetadata,
            String solverStatus,
            List<String> explanations) {
    }
}
