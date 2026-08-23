package dev.bob.openmarket.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @GetMapping("/")
    public java.util.Map<String, Object> root() {
        return java.util.Map.of(
            "service", "auth",
            "status", "ok",
            "version", "0.1.0"
        );
    }
}
