package dev.bob.openmarket.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the probe contract the Docker/K8s healthchecks and the gateway
 * depend on: /health/live is dependency-free, /health/ready degrades to 503
 * when Postgres is unreachable — without leaking the JDBC exception.
 */
class HealthControllerTest {

    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        mvc = MockMvcBuilders.standaloneSetup(new HealthController(jdbc)).build();
    }

    @Test
    void live_answers_200_regardless_of_dependencies() throws Exception {
        mvc.perform(get("/health/live"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("live"));
    }

    @Test
    void ready_answers_200_when_postgres_responds() throws Exception {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        mvc.perform(get("/health/ready"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ready"));
    }

    @Test
    void ready_answers_503_without_leaking_the_exception_when_postgres_is_down() throws Exception {
        when(jdbc.queryForObject("SELECT 1", Integer.class))
            .thenThrow(new RuntimeException("FATAL: password authentication failed for user \"om\""));

        mvc.perform(get("/health/ready"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error").value("postgres unreachable"));
    }
}
