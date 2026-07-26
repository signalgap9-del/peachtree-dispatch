package com.atmospath.platform.compliance;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.atmospath.platform.compliance.dto.DataExportResponse;
import com.atmospath.platform.compliance.dto.SavedRouteExport;
import com.atmospath.platform.config.SecurityConfig;
import com.atmospath.platform.ratelimit.RateLimitProperties;
import com.atmospath.platform.ratelimit.RateLimitRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises the compliance endpoints through the real security filter
 * chain: unauthenticated requests must get 401, authenticated requests
 * reach the controller.
 */
@WebMvcTest(value = ComplianceController.class, properties = {
        "atmospath.auth.enabled=true",
        "atmospath.saas.enabled=true"})
@Import(SecurityConfig.class)
class ComplianceControllerTests {

    private static final String SUBJECT = "google-oauth2|user123";
    private static final String EMAIL = "user@example.com";
    private static final UUID TENANT_ID = UUID.nameUUIDFromBytes(
            ("tenant:" + SUBJECT).getBytes(StandardCharsets.UTF_8));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataExportService dataExportService;

    @MockitoBean
    private AccountErasureService accountErasureService;

    // Prevents issuer discovery over the network during context startup.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    // The web slice also scans servlet filters; these satisfy RateLimitFilter.
    // The mocked properties report enabled() == false, so the filter is a no-op.
    @MockitoBean
    private RateLimitRepository rateLimitRepository;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    @MockitoBean
    private MeterRegistry meterRegistry;

    @Test
    void dataExportRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/me/data-export"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accountDeletionRequiresAuthentication() throws Exception {
        mockMvc.perform(delete("/api/v1/me/account"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dataExportReturnsJsonAttachment() throws Exception {
        DataExportResponse export = new DataExportResponse(
                SUBJECT,
                null,
                List.of(new SavedRouteExport(UUID.randomUUID(), "Morning loop",
                        "Atlanta hub", "Decatur", "car", 55, true, Instant.now())),
                List.of(),
                List.of(),
                Map.of("ROUTE_PLAN", 3),
                Instant.now());
        when(dataExportService.export(eq(TENANT_ID), eq(SUBJECT), eq(EMAIL)))
                .thenReturn(export);

        mockMvc.perform(get("/api/v1/me/data-export")
                        .with(jwt().jwt(jwt -> jwt.subject(SUBJECT).claim("email", EMAIL))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment; filename=\"atmospath-data-export-")))
                .andExpect(jsonPath("$.subject").value(SUBJECT))
                .andExpect(jsonPath("$.savedRoutes[0].name").value("Morning loop"))
                .andExpect(jsonPath("$.usageSummary.ROUTE_PLAN").value(3));
    }

    @Test
    void accountDeletionReturnsNoContentAndDelegates() throws Exception {
        when(accountErasureService.erase(TENANT_ID, EMAIL)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/me/account")
                        .with(jwt().jwt(jwt -> jwt.subject(SUBJECT).claim("email", EMAIL))))
                .andExpect(status().isNoContent());

        verify(accountErasureService).erase(TENANT_ID, EMAIL);
    }

    @Test
    void accountDeletionIsIdempotentForUnknownUsers() throws Exception {
        when(accountErasureService.erase(TENANT_ID, EMAIL)).thenReturn(false);

        mockMvc.perform(delete("/api/v1/me/account")
                        .with(jwt().jwt(jwt -> jwt.subject(SUBJECT).claim("email", EMAIL))))
                .andExpect(status().isNoContent());
    }
}
