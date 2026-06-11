package com.atmospath.platform;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping({"/health", "/api/v1/health"})
    Map<String, String> health() {
        return Map.of("status", "healthy", "service", "atmospath-platform-api");
    }
}
