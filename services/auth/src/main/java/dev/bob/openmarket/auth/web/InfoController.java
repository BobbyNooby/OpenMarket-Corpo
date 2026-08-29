package dev.bob.openmarket.auth.web;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Service identity card — the repo-wide convention for the `/` route. */
@RestController
public class InfoController {

    @GetMapping("/")
    @Operation(summary = "Service info", hidden = true)
    public Map<String, String> info() {
        return Map.of(
            "service", "auth",
            "status", "ok",
            "description", "OpenMarket v2 — auth & users (JWT identity service)");
    }
}
