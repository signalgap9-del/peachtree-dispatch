package com.atmospath.platform.llm.nl2opt;

import java.util.List;

/**
 * Structured VRP constraint model extracted from natural language by the
 * {@code nl2opt_extraction} prompt. The records mirror the JSON schema the
 * LLM is asked to produce; {@link #JSON_SCHEMA} is the schema text injected
 * into the prompt's {@code {schema}} variable.
 */
public final class VrpConstraintSchema {

    private VrpConstraintSchema() {
    }

    /** JSON schema handed to the LLM so extraction output stays parseable. */
    public static final String JSON_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "stops": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": { "type": "string" },
                      "type": { "type": "string", "enum": ["origin", "destination", "pickup", "delivery", "service"] },
                      "timeWindow": {
                        "type": ["object", "null"],
                        "properties": {
                          "earliest": { "type": ["string", "null"], "pattern": "^([01]\\\\d|2[0-3]):[0-5]\\\\d$" },
                          "latest": { "type": ["string", "null"], "pattern": "^([01]\\\\d|2[0-3]):[0-5]\\\\d$" }
                        }
                      },
                      "priority": { "type": "integer", "minimum": 0 }
                    },
                    "required": ["name"]
                  }
                },
                "vehicle": {
                  "type": ["object", "null"],
                  "properties": {
                    "type": { "type": "string", "enum": ["car", "truck", "van", "motorcycle", "bicycle"] },
                    "hazmat": { "type": "boolean" },
                    "capacityKg": { "type": ["integer", "null"] }
                  }
                },
                "departure": {
                  "type": ["object", "null"],
                  "properties": {
                    "time": { "type": "string", "pattern": "^([01]\\\\d|2[0-3]):[0-5]\\\\d$" },
                    "flexibility": { "type": ["string", "null"], "enum": ["strict", "flexible", null] }
                  }
                },
                "arrival": {
                  "type": ["object", "null"],
                  "properties": {
                    "time": { "type": "string", "pattern": "^([01]\\\\d|2[0-3]):[0-5]\\\\d$" },
                    "flexibility": { "type": ["string", "null"], "enum": ["strict", "flexible", null] }
                  }
                },
                "softConstraints": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "type": { "type": "string" },
                      "target": { "type": "string" },
                      "weight": { "type": "number", "minimum": 0, "maximum": 1 },
                      "reason": { "type": "string" }
                    },
                    "required": ["type", "weight"]
                  }
                },
                "hardConstraints": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "type": { "type": "string" },
                      "target": { "type": "string" },
                      "reason": { "type": "string" }
                    },
                    "required": ["type"]
                  }
                },
                "objective": { "type": ["string", "null"] }
              },
              "required": ["stops"]
            }
            """;

    public record VrpConstraints(
            List<StopConstraint> stops,
            VehicleConstraint vehicle,
            TimeConstraint departure,
            TimeConstraint arrival,
            List<SoftConstraint> softConstraints,
            List<HardConstraint> hardConstraints,
            String objective) {
    }

    public record StopConstraint(String name, String type, TimeWindow timeWindow, int priority) {
    }

    public record VehicleConstraint(String type, boolean hazmat, Integer capacityKg) {
    }

    /** Departure or arrival time with optional flexibility hint. */
    public record TimeConstraint(String time, String flexibility) {
    }

    public record TimeWindow(String earliest, String latest) {
    }

    public record SoftConstraint(String type, String target, double weight, String reason) {
    }

    public record HardConstraint(String type, String target, String reason) {
    }
}
