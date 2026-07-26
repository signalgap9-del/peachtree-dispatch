package com.atmospath.platform.compliance;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import com.atmospath.platform.compliance.dto.DataExportResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service GDPR endpoints for the authenticated user:
 * {@code GET /api/v1/me/data-export} (Article 15/20 portability) and
 * {@code DELETE /api/v1/me/account} (Article 17 erasure, idempotent).
 * Both require authentication; see SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/me")
@ConditionalOnExpression(
        "'${atmospath.auth.enabled:false}' == 'true' and '${atmospath.saas.enabled:false}' == 'true'")
public class ComplianceController {

    private static final Logger log = LoggerFactory.getLogger(ComplianceController.class);

    private final DataExportService dataExportService;
    private final AccountErasureService accountErasureService;

    public ComplianceController(DataExportService dataExportService,
                                AccountErasureService accountErasureService) {
        this.dataExportService = dataExportService;
        this.accountErasureService = accountErasureService;
    }

    @GetMapping("/data-export")
    ResponseEntity<DataExportResponse> dataExport(@AuthenticationPrincipal Jwt jwt) {
        DataExportResponse export =
                dataExportService.export(tenantId(jwt), jwt.getSubject(), email(jwt));
        String filename = "atmospath-data-export-" + LocalDate.now(ZoneOffset.UTC) + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(export);
    }

    @DeleteMapping("/account")
    ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal Jwt jwt) {
        boolean erased = accountErasureService.erase(tenantId(jwt), email(jwt));
        log.info("Account erasure requested: subject={} erased={}", jwt.getSubject(), erased);
        return ResponseEntity.noContent().build();
    }

    /** Matches the tenant derivation in TenantContextResolver. */
    private static UUID tenantId(Jwt jwt) {
        return UUID.nameUUIDFromBytes(("tenant:" + jwt.getSubject()).getBytes(StandardCharsets.UTF_8));
    }

    private static String email(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return email == null ? "" : email;
    }
}
