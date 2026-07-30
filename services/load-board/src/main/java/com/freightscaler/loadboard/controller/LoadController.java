package com.freightscaler.loadboard.controller;

import com.freightscaler.loadboard.model.Load;
import com.freightscaler.loadboard.service.CreateLoadRequest;
import com.freightscaler.loadboard.service.LoadPageResponse;
import com.freightscaler.loadboard.service.LoadService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/loads")
public class LoadController {

    private final LoadService loadService;
    private final JdbcTemplate jdbc;

    public LoadController(LoadService loadService, JdbcTemplate jdbc) {
        this.loadService = loadService;
        this.jdbc = jdbc;
    }

    @PostMapping
    public ResponseEntity<Load> createLoad(@Valid @RequestBody CreateLoadRequest request) {
        Load created = loadService.createLoad(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public Load getLoad(@PathVariable Long id) {
        return loadService.getLoad(id);
    }

    @GetMapping
    public LoadPageResponse listLoads(
            @RequestParam(required = false) String corridor,
            @RequestParam(required = false) String cargoType,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return loadService.listLoads(corridor, cargoType, cursor, limit);
    }

    @PostMapping("/{id}/match")
    public ResponseEntity<Load> matchLoad(@PathVariable Long id) {
        Load matched = loadService.matchLoad(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(matched);
    }

    @GetMapping("/summary")
    public List<Map<String, Object>> summary() {
        return jdbc.queryForList(
                """
                SELECT corridor_id, cargo_type, open_count,
                       median_rate, avg_risk, oldest_load
                  FROM mv_open_loads_summary
                 ORDER BY open_count DESC
                """);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "load-board-service");
    }
}
