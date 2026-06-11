package com.atmospath.platform.risk;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
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

    public RiskController(RiskEngineGateway riskEngine) {
        this.riskEngine = riskEngine;
    }

    @GetMapping("/places/search")
    JsonNode searchPlaces(@RequestParam("q") @NotBlank String query) {
        return riskEngine.get("/places/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
    }

    @PostMapping("/directions")
    JsonNode directions(@RequestBody JsonNode request) {
        return riskEngine.post("/directions", request);
    }

    @GetMapping("/risk/national")
    JsonNode nationalRisk() {
        return riskEngine.get("/risk/national");
    }

    @PostMapping("/risk/location")
    JsonNode locationRisk(@RequestBody JsonNode request) {
        return riskEngine.post("/risk/location", request);
    }

    @GetMapping("/network")
    JsonNode network(@RequestParam(value = "vehicle_type", required = false) String vehicleType) {
        return riskEngine.get(vehicleType == null ? "/network" : "/network?vehicle_type=" + vehicleType);
    }
}
