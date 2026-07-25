package com.atmospath.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.atmospath.platform.llm.nl2opt.VrpConstraintValidator;
import com.atmospath.platform.llm.nl2opt.VrpConstraintValidator.ValidationResult;
import org.junit.jupiter.api.Test;

class VrpConstraintValidatorTests {

    private final VrpConstraintValidator validator = new VrpConstraintValidator();

    @Test
    void validConstraintJsonPasses() {
        String json = """
                {
                  "stops": [
                    { "name": "Denver", "type": "origin", "timeWindow": null, "priority": 0 },
                    { "name": "Boulder", "type": "destination",
                      "timeWindow": { "earliest": null, "latest": "09:00" }, "priority": 0 }
                  ],
                  "vehicle": { "type": "car", "hazmat": false, "capacityKg": null },
                  "departure": null,
                  "arrival": { "time": "09:00", "flexibility": "strict" },
                  "softConstraints": [
                    { "type": "weather_avoidance", "target": "hail", "weight": 0.8, "reason": "forecast hail" }
                  ],
                  "hardConstraints": [
                    { "type": "arrival_deadline", "target": "09:00", "reason": "must arrive by 09:00" }
                  ],
                  "objective": "minimize_time"
                }
                """;

        assertThat(validator.validate(json).valid()).isTrue();
    }

    @Test
    void emptyStopsFail() {
        ValidationResult result = validator.validate("""
                { "stops": [], "vehicle": { "type": "van", "hazmat": false, "capacityKg": null } }
                """);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("stops must not be empty");
    }

    @Test
    void invalidVehicleTypeFails() {
        ValidationResult result = validator.validate("""
                { "stops": [ { "name": "Denver", "type": "origin", "timeWindow": null, "priority": 0 } ],
                  "vehicle": { "type": "hovercraft", "hazmat": false, "capacityKg": null } }
                """);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("vehicle.type"));
    }

    @Test
    void invalidTimeFormatFails() {
        ValidationResult result = validator.validate("""
                { "stops": [ { "name": "Denver", "type": "origin", "timeWindow": null, "priority": 0 } ],
                  "departure": { "time": "7:30 am", "flexibility": "strict" } }
                """);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("departure.time must be 24-hour HH:mm");
    }

    @Test
    void softConstraintWeightOutOfRangeFails() {
        ValidationResult result = validator.validate("""
                { "stops": [ { "name": "Denver", "type": "origin", "timeWindow": null, "priority": 0 } ],
                  "softConstraints": [ { "type": "weather_avoidance", "target": "hail", "weight": 1.5, "reason": "x" } ] }
                """);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("weight must be between 0 and 1"));
    }

    @Test
    void malformedJsonFails() {
        ValidationResult result = validator.validate("not json at all");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("could not be parsed"));
    }
}
