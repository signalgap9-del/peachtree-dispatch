package com.atmospath.platform.risk;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
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

    @PostMapping("/routes/multi-stop")
    JsonNode multiStopRoute(@RequestBody JsonNode request) {
        return riskEngine.post("/routes/multi-stop", request);
    }

    @PostMapping("/routes/multi-stop/optimize")
    JsonNode optimizeMultiStopRoute(@RequestBody JsonNode request) {
        return riskEngine.post("/routes/multi-stop/optimize", request);
    }

    @PostMapping("/vrp/solve")
    JsonNode solveVrp(@RequestBody JsonNode request) {
        return riskEngine.post("/vrp/solve", request);
    }

    @PostMapping("/graphql")
    JsonNode graphql(@RequestBody JsonNode request) {
        return riskEngine.post("/graphql", request);
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
    JsonNode locationRisk(@RequestBody JsonNode request) {
        return riskEngine.post("/risk/location", request);
    }

    @GetMapping("/road-events/feeds")
    JsonNode roadEventFeeds(
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "limit", defaultValue = "30") int limit) {
        StringBuilder path = new StringBuilder("/road-events/feeds?limit=").append(limit);
        if (state != null && !state.isBlank()) {
            path.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
        }
        return riskEngine.get(path.toString());
    }

}
