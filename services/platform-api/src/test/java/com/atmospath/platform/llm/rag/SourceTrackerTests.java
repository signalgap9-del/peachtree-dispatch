package com.atmospath.platform.llm.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SourceTrackerTests {

    private SourceTracker tracker;

    private static final UUID OBS_ID_1 = UUID.fromString("abc12345-0000-0000-0000-000000000001");
    private static final UUID OBS_ID_2 = UUID.fromString("def67890-0000-0000-0000-000000000002");
    private static final UUID ROUTE_ID_1 = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ROUTE_ID_2 = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final Instant OBS_TIME_1 = Instant.parse("2026-03-15T10:00:00Z");
    private static final Instant OBS_TIME_2 = Instant.parse("2026-04-20T14:30:00Z");

    private static final SourceReference SOURCE_1 = new SourceReference(
            OBS_ID_1, "route_risk_observation", OBS_TIME_1, ROUTE_ID_1, 85, 0.92,
            "Flood warning with heavy precipitation");

    private static final SourceReference SOURCE_2 = new SourceReference(
            OBS_ID_2, "route_risk_observation", OBS_TIME_2, ROUTE_ID_2, 62, 0.78,
            "Wind advisory on coastal section");

    @BeforeEach
    void setUp() {
        tracker = new SourceTracker();
    }

    @Test
    void responseMentioningDateCitesSource() {
        String response = "Based on the 2026-03-15 observation, this route has significant flood risk.";

        TrackedResponse tracked = tracker.trackSources(response, List.of(SOURCE_1, SOURCE_2));

        assertThat(tracked.citedSources()).hasSize(1);
        assertThat(tracked.citedSources().get(0).observationId()).isEqualTo(OBS_ID_1);
    }

    @Test
    void responseMentioningNothingCitesNoSources() {
        String response = "This route has moderate risk due to current weather conditions.";

        TrackedResponse tracked = tracker.trackSources(response, List.of(SOURCE_1, SOURCE_2));

        assertThat(tracked.citedSources()).isEmpty();
        assertThat(tracked.citationFooter()).isEmpty();
    }

    @Test
    void responseMentioningObservationIdCitesSource() {
        String response = "Historical data (abc12345) shows repeated flooding on this segment.";

        TrackedResponse tracked = tracker.trackSources(response, List.of(SOURCE_1));

        assertThat(tracked.citedSources()).hasSize(1);
        assertThat(tracked.citedSources().get(0).observationId()).isEqualTo(OBS_ID_1);
    }

    @Test
    void responseMentioningRouteIdAndRiskCitesSource() {
        String response = "Route " + ROUTE_ID_1 + " previously scored 85 during a flood event.";

        TrackedResponse tracked = tracker.trackSources(response, List.of(SOURCE_1, SOURCE_2));

        assertThat(tracked.citedSources()).hasSize(1);
        assertThat(tracked.citedSources().get(0).routeId()).isEqualTo(ROUTE_ID_1);
    }

    @Test
    void multipleSourcesCited() {
        String response = "On 2026-03-15 flooding was observed, and on 2026-04-20 wind issues were reported.";

        TrackedResponse tracked = tracker.trackSources(response, List.of(SOURCE_1, SOURCE_2));

        assertThat(tracked.citedSources()).hasSize(2);
    }

    @Test
    void citationFooterFormatCorrect() {
        String footer = tracker.buildCitationFooter(List.of(SOURCE_1));

        assertThat(footer).contains("---");
        assertThat(footer).contains("근거:");
        assertThat(footer).contains("[2026-03-15]");
        assertThat(footer).contains(ROUTE_ID_1.toString());
        assertThat(footer).contains("risk 85/100");
        assertThat(footer).contains("(관측 #abc12345)");
    }

    @Test
    void citationFooterWithMultipleSources() {
        String footer = tracker.buildCitationFooter(List.of(SOURCE_1, SOURCE_2));

        assertThat(footer).contains("[2026-03-15]");
        assertThat(footer).contains("[2026-04-20]");
    }

    @Test
    void citationFooterWithEmptyListReturnsEmpty() {
        String footer = tracker.buildCitationFooter(List.of());

        assertThat(footer).isEmpty();
    }

    @Test
    void trackSourcesWithNullResponseReturnsEmpty() {
        TrackedResponse tracked = tracker.trackSources(null, List.of(SOURCE_1));

        assertThat(tracked.response()).isEmpty();
        assertThat(tracked.citedSources()).isEmpty();
        assertThat(tracked.citationFooter()).isEmpty();
    }

    @Test
    void trackSourcesWithEmptySourcesReturnsNoCitations() {
        String response = "Some response about 2026-03-15.";

        TrackedResponse tracked = tracker.trackSources(response, List.of());

        assertThat(tracked.response()).isEqualTo(response);
        assertThat(tracked.citedSources()).isEmpty();
    }
}
