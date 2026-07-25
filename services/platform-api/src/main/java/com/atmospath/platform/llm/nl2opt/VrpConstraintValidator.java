package com.atmospath.platform.llm.nl2opt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.SoftConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.StopConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.TimeConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.TimeWindow;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.VehicleConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.VrpConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validates LLM-extracted VRP constraints before they reach the solver.
 * Phase 2 uses this in a validate-and-repair loop: invalid output is sent
 * back to the LLM with the collected errors for a corrected retry.
 */
public class VrpConstraintValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Set<String> VALID_VEHICLE_TYPES =
            Set.of("car", "truck", "van", "motorcycle", "bicycle");
    private static final Pattern TIME_24H = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    /** Outcome of a validation pass; {@code errors} is empty when valid. */
    public record ValidationResult(boolean valid, List<String> errors) {

        static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        static ValidationResult failed(List<String> errors) {
            return new ValidationResult(false, List.copyOf(errors));
        }
    }

    /** Parses raw LLM JSON output and validates it against the schema. */
    public ValidationResult validate(String json) {
        VrpConstraints constraints;
        try {
            constraints = MAPPER.readValue(json, VrpConstraints.class);
        } catch (Exception ex) {
            return ValidationResult.failed(List.of("constraints JSON could not be parsed: " + ex.getMessage()));
        }
        return validate(constraints);
    }

    public ValidationResult validate(VrpConstraints constraints) {
        List<String> errors = new ArrayList<>();
        if (constraints == null) {
            return ValidationResult.failed(List.of("constraints object is null"));
        }
        if (constraints.stops() == null || constraints.stops().isEmpty()) {
            errors.add("stops must not be empty");
        } else {
            for (int i = 0; i < constraints.stops().size(); i++) {
                StopConstraint stop = constraints.stops().get(i);
                if (stop.name() == null || stop.name().isBlank()) {
                    errors.add("stops[" + i + "].name is required");
                }
                if (stop.priority() < 0) {
                    errors.add("stops[" + i + "].priority must be >= 0");
                }
                checkTimeWindow(stop.timeWindow(), "stops[" + i + "].timeWindow", errors);
            }
        }
        checkVehicle(constraints.vehicle(), errors);
        checkTime(constraints.departure(), "departure", errors);
        checkTime(constraints.arrival(), "arrival", errors);
        if (constraints.softConstraints() != null) {
            for (int i = 0; i < constraints.softConstraints().size(); i++) {
                SoftConstraint soft = constraints.softConstraints().get(i);
                if (soft.weight() < 0.0 || soft.weight() > 1.0) {
                    errors.add("softConstraints[" + i + "].weight must be between 0 and 1");
                }
            }
        }
        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(errors);
    }

    private void checkVehicle(VehicleConstraint vehicle, List<String> errors) {
        if (vehicle == null || vehicle.type() == null) {
            return;
        }
        if (!VALID_VEHICLE_TYPES.contains(vehicle.type().toLowerCase(Locale.ROOT))) {
            errors.add("vehicle.type must be one of " + VALID_VEHICLE_TYPES);
        }
        if (vehicle.capacityKg() != null && vehicle.capacityKg() <= 0) {
            errors.add("vehicle.capacityKg must be positive");
        }
    }

    private void checkTime(TimeConstraint time, String field, List<String> errors) {
        if (time == null || time.time() == null) {
            return;
        }
        if (!TIME_24H.matcher(time.time()).matches()) {
            errors.add(field + ".time must be 24-hour HH:mm");
        }
    }

    private void checkTimeWindow(TimeWindow window, String field, List<String> errors) {
        if (window == null) {
            return;
        }
        if (window.earliest() != null && !TIME_24H.matcher(window.earliest()).matches()) {
            errors.add(field + ".earliest must be 24-hour HH:mm");
        }
        if (window.latest() != null && !TIME_24H.matcher(window.latest()).matches()) {
            errors.add(field + ".latest must be 24-hour HH:mm");
        }
    }
}
