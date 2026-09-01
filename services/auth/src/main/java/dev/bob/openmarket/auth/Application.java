package dev.bob.openmarket.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling // RateLimiter's periodic stale-bucket sweep
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
