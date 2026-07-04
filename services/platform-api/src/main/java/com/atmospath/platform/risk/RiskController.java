package com.atmospath.platform.risk;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.atmospath.platform.account.EntitlementService;
import com.atmospath.platform.account.MeteredFeature;
import com.atmospath.platform.account.TenantContextResolver;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping({"/api/v1", ""})
public class RiskController {
    private final RiskEngineGateway riskEngine;
    private final TenantContextResolver tenantContextResolver;
    private final EntitlementService entitlements;

    public RiskController(
            RiskEngineGateway riskEngine,
            TenantContextResolver tenantContextResolver,
            EntitlementService entitlements) {
        this.riskEngine = riskEngine;
        this.tenantContextResolver = tenantContextResolver;
        this.entitlements = entitlements;
    }

    @GetMapping("/places/search")
    JsonNode searchPlaces(
            @RequestParam("q") @NotBlank String query,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        entitlements.consume(tenantContextResolver.resolve(jwt, request), MeteredFeature.PLACE_SEARCH);
        return riskEngine.get("/places/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
    }

    @PostMapping("/directions")
    JsonNode directions(
            @RequestBody JsonNode body,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        entitlements.consume(tenantContextResolver.resolve(jwt, request), MeteredFeature.ROUTE_PLAN);
        return riskEngine.post("/directions", body);
    }

    @GetMapping("/risk/national")
    JsonNode nationalRisk() {
        return riskEngine.get("/risk/national");
    }

    @GetMapping("/risk/weather-snapshot")
    JsonNode weatherSnapshot() {
        return riskEngine.get("/risk/weather-snapshot");
    }

    @GetMapping("/risk/weather-raster")
    JsonNode weatherRaster() {
        return riskEngine.get("/risk/weather-raster");
    }

    @GetMapping(value = "/risk/weather-raster.png", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> weatherRasterPng() {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noCache())
                .body(riskEngine.getBytes("/risk/weather-raster.png"));
    }

    @PostMapping("/risk/location")
    JsonNode locationRisk(
            @RequestBody JsonNode body,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        entitlements.consume(tenantContextResolver.resolve(jwt, request), MeteredFeature.LOCATION_RISK);
        return riskEngine.post("/risk/location", body);
    }

}
