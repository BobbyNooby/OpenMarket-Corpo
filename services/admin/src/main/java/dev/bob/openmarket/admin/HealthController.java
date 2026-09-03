package dev.bob.openmarket.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness (process is up) vs readiness (dependencies reachable) —
 * Docker/K8s probes and the gateway healthcheck hit these. Mirrors the
 * auth service's contract, minus the leak: the readiness error body is a
 * fixed string, never the JDBC exception.
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health/live")
    public Map<String, String> live() {
        return Map.of("status", "live");
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "ready"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "error", "error", "postgres unreachable"));
        }
    }
}
