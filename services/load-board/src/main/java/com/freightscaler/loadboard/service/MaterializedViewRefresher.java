package com.freightscaler.loadboard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MaterializedViewRefresher {

    private static final Logger log = LoggerFactory.getLogger(MaterializedViewRefresher.class);

    private final JdbcTemplate jdbc;

    public MaterializedViewRefresher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedRate = 30_000)
    public void refreshOpenLoadsSummary() {
        long start = System.currentTimeMillis();
        try {
            jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_open_loads_summary");
            long elapsed = System.currentTimeMillis() - start;
            log.info("Refreshed mv_open_loads_summary in {} ms", elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Failed to refresh mv_open_loads_summary after {} ms: {}",
                    elapsed, e.getMessage());
        }
    }
}
