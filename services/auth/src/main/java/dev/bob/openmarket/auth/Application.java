package dev.bob.openmarket.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

@SpringBootApplication
@RestController
public class Application {

    private final DataSource dataSource;

    public Application(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @GetMapping("/")
    public Map<String, Object> root() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version()")) {
            rs.next();
            return Map.of(
                "service", "auth",
                "status", "ok",
                "postgres", rs.getString("version")
            );
        } catch (Exception e) {
            return Map.of(
                "service", "auth",
                "status", "error",
                "error", e.getMessage()
            );
        }
    }
}
