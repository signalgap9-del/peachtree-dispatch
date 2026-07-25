package com.atmospath.platform.llm.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.HardConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.StopConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.TimeConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.VehicleConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.VrpConstraints;
import org.junit.jupiter.api.Test;

class ConstraintDiffTests {

    private static final VrpConstraints BASE = new VrpConstraints(
            List.of(new StopConstraint("Seattle", "origin", null, 0),
                    new StopConstraint("Miami", "destination", null, 0)),
            new VehicleConstraint("truck", false, null),
            new TimeConstraint("08:00", "strict"),
            null,
            List.of(),
            List.of(new HardConstraint("avoid_corridor", "I-5", "user request")),
            "minimize_risk");

    @Test
    void modifyDepartureTimeOnlyChangesDeparture() {
        var diff = new ConstraintDiff(Map.of("departure.time", "10:00"), List.of(), List.of());

        VrpConstraints result = diff.applyTo(BASE);

        assertThat(result.departure().time()).isEqualTo("10:00");
        assertThat(result.departure().flexibility()).isEqualTo("strict");
        // Everything else unchanged
        assertThat(result.stops()).hasSize(2);
        assertThat(result.vehicle().type()).isEqualTo("truck");
        assertThat(result.hardConstraints()).hasSize(1);
        assertThat(result.objective()).isEqualTo("minimize_risk");
    }

    @Test
    void modifyDepartureEarliestAlias() {
        var diff = new ConstraintDiff(Map.of("departure.earliest", "09:30"), List.of(), List.of());

        VrpConstraints result = diff.applyTo(BASE);

        assertThat(result.departure().time()).isEqualTo("09:30");
    }

    @Test
    void addAvoidConstraintAppendsToList() {
        var diff = new ConstraintDiff(
                Map.of(),
                List.of("{\"type\":\"avoid_corridor\",\"target\":\"I-90\"}"),
                List.of());

        VrpConstraints result = diff.applyTo(BASE);

        assertThat(result.hardConstraints()).hasSize(2);
        assertThat(result.hardConstraints().get(1).type()).isEqualTo("avoid_corridor");
        assertThat(result.hardConstraints().get(1).target()).isEqualTo("I-90");
    }

    @Test
    void removeConstraintByTarget() {
        var diff = new ConstraintDiff(Map.of(), List.of(), List.of("I-5"));

        VrpConstraints result = diff.applyTo(BASE);

        assertThat(result.hardConstraints()).isEmpty();
    }

    @Test
    void removeConstraintByType() {
        var diff = new ConstraintDiff(Map.of(), List.of(), List.of("avoid_corridor"));

        VrpConstraints result = diff.applyTo(BASE);

        assertThat(result.hardConstraints()).isEmpty();
    }

    @Test
    void multipleChangesInOneDiff() {
        var diff = new ConstraintDiff(
                Map.of("departure.time", "11:00", "vehicle.type", "van"),
                List.of("{\"type\":\"hazmat_route\",\"target\":\"downtown\"}"),
                List.of("I-5"));

        VrpConstraints result = diff.applyTo(BASE);

        assertThat(result.departure().time()).isEqualTo("11:00");
        assertThat(result.vehicle().type()).isEqualTo("van");
        assertThat(result.hardConstraints()).hasSize(1);
        assertThat(result.hardConstraints().get(0).type()).isEqualTo("hazmat_route");
    }

    @Test
    void applyToNullConstraintsCreatesNew() {
        var diff = new ConstraintDiff(
                Map.of("departure.time", "07:00"),
                List.of("{\"type\":\"avoid_corridor\",\"target\":\"I-5\"}"),
                List.of());

        VrpConstraints result = diff.applyTo(null);

        assertThat(result).isNotNull();
        assertThat(result.departure().time()).isEqualTo("07:00");
        assertThat(result.hardConstraints()).hasSize(1);
        assertThat(result.stops()).isEmpty();
    }

    @Test
    void emptyDiffReturnsEquivalentConstraints() {
        var diff = new ConstraintDiff(Map.of(), List.of(), List.of());

        VrpConstraints result = diff.applyTo(BASE);

        assertThat(result.stops()).hasSize(2);
        assertThat(result.departure().time()).isEqualTo("08:00");
        assertThat(result.vehicle().type()).isEqualTo("truck");
        assertThat(result.hardConstraints()).hasSize(1);
    }

    @Test
    void invalidConstraintJsonIsIgnored() {
        var diff = new ConstraintDiff(Map.of(), List.of("not valid json"), List.of());

        VrpConstraints result = diff.applyTo(BASE);

        // Original constraint preserved, invalid one skipped
        assertThat(result.hardConstraints()).hasSize(1);
    }
}
