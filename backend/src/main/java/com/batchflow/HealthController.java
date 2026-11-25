package com.batchflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Intentionally has no role condition: both API and worker processes expose it.
 */
@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    private final String role;
    private final String queueMode;

    public HealthController(
            JdbcTemplate jdbc,
            @Value("${app.role:api}") String role,
            @Value("${app.queue-mode:database}") String queueMode
    ) {
        this.jdbc = jdbc;
        this.role = role;
        this.queueMode = queueMode;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Health> health() {
        try {
            Integer result = jdbc.queryForObject("SELECT 1", Integer.class);
            if (Integer.valueOf(1).equals(result)) {
                return ResponseEntity.ok(new Health("UP", role, queueMode));
            }
        } catch (DataAccessException ignored) {
            // Return only public health information, never database details.
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Health("DOWN", role, queueMode));
    }

    public record Health(String status, String role, String queueMode) {}
}
