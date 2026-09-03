package dev.bob.openmarket.auth.web;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness (process is up) vs readiness (dependencies reachable) —
 * Docker/K8s probes and the gateway healthcheck hit these.
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health/live")
    @Operation(summary = "Liveness probe", hidden = true)
    public Map<String, String> live() {
        return Map.of("status", "live");
    }

    @GetMapping("/health/ready")
    @Operation(summary = "Readiness probe (checks Postgres)", hidden = true)
    public ResponseEntity<Map<String, String>> ready() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "ready"));
        } catch (Exception e) {
            // fixed string on purpose: this endpoint is public, and the raw
            // JDBC message would disclose DB hosts/users to the internet
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "error", "error", "postgres unreachable"));
        }
    }
}
