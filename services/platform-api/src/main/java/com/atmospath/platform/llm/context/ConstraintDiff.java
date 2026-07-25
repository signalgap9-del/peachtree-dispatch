package com.atmospath.platform.llm.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.HardConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.SoftConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.StopConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.TimeConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.VehicleConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.VrpConstraints;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Represents a partial constraint modification detected from a user's
 * follow-up message. Applied immutably: {@link #applyTo} returns a new
 * {@link VrpConstraints} without mutating the original.
 *
 * <p>Examples:
 * <ul>
 *   <li>"2시간 뒤에 출발하면?" → modifiedFields: {"departure.time": "10:00"}</li>
 *   <li>"I-5 피할 수 있어?" → addedConstraints: [{"type":"avoid_corridor","target":"I-5"}]</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConstraintDiff(
        Map<String, Object> modifiedFields,
        List<String> addedConstraints,
        List<String> removedConstraints) {

    public ConstraintDiff {
        modifiedFields = modifiedFields != null ? Map.copyOf(modifiedFields) : Map.of();
        addedConstraints = addedConstraints != null ? List.copyOf(addedConstraints) : List.of();
        removedConstraints = removedConstraints != null ? List.copyOf(removedConstraints) : List.of();
    }

    /** Apply this diff to existing constraints, producing a new instance. */
    public VrpConstraints applyTo(VrpConstraints existing) {
        if (existing == null) {
            existing = new VrpConstraints(List.of(), null, null, null, List.of(), List.of(), null);
        }

        List<StopConstraint> stops = existing.stops() != null ? new ArrayList<>(existing.stops()) : new ArrayList<>();
        VehicleConstraint vehicle = existing.vehicle();
        TimeConstraint departure = existing.departure();
        TimeConstraint arrival = existing.arrival();
        List<SoftConstraint> soft = existing.softConstraints() != null
                ? new ArrayList<>(existing.softConstraints()) : new ArrayList<>();
        List<HardConstraint> hard = existing.hardConstraints() != null
                ? new ArrayList<>(existing.hardConstraints()) : new ArrayList<>();
        String objective = existing.objective();

        // Apply field modifications via dot-notation paths
        for (var entry : modifiedFields.entrySet()) {
            String path = entry.getKey();
            String value = entry.getValue() != null ? entry.getValue().toString() : null;

            switch (path) {
                case "departure.time", "departure.earliest" ->
                        departure = new TimeConstraint(value, departure != null ? departure.flexibility() : null);
                case "departure.flexibility" ->
                        departure = new TimeConstraint(departure != null ? departure.time() : null, value);
                case "arrival.time", "arrival.latest" ->
                        arrival = new TimeConstraint(value, arrival != null ? arrival.flexibility() : null);
                case "arrival.flexibility" ->
                        arrival = new TimeConstraint(arrival != null ? arrival.time() : null, value);
                case "vehicle.type" ->
                        vehicle = new VehicleConstraint(value,
                                vehicle != null && vehicle.hazmat(),
                                vehicle != null ? vehicle.capacityKg() : null);
                case "vehicle.hazmat" ->
                        vehicle = new VehicleConstraint(
                                vehicle != null ? vehicle.type() : null,
                                Boolean.parseBoolean(value),
                                vehicle != null ? vehicle.capacityKg() : null);
                case "objective" -> objective = value;
                default -> { /* unrecognized path: ignore gracefully */ }
            }
        }

        // Add new hard constraints from JSON strings
        for (String json : addedConstraints) {
            HardConstraint hc = parseHardConstraint(json);
            if (hc != null) {
                hard.add(hc);
            }
        }

        // Remove constraints by type or target match
        if (!removedConstraints.isEmpty()) {
            hard.removeIf(hc -> removedConstraints.contains(hc.type())
                    || removedConstraints.contains(hc.target()));
            soft.removeIf(sc -> removedConstraints.contains(sc.type())
                    || removedConstraints.contains(sc.target()));
        }

        return new VrpConstraints(stops, vehicle, departure, arrival, soft, hard, objective);
    }

    /** Minimal JSON parsing for {"type":"...","target":"...","reason":"..."}. */
    private static HardConstraint parseHardConstraint(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            String type = extractJsonField(json, "type");
            String target = extractJsonField(json, "target");
            String reason = extractJsonField(json, "reason");
            if (type == null) {
                return null;
            }
            return new HardConstraint(type, target, reason);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) {
            return null;
        }
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) {
            return null;
        }
        int start = json.indexOf('"', colonIdx + 1);
        if (start < 0) {
            return null;
        }
        int end = json.indexOf('"', start + 1);
        if (end < 0) {
            return null;
        }
        return json.substring(start + 1, end);
    }
}
