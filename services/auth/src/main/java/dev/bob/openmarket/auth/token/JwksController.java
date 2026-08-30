package dev.bob.openmarket.auth.token;

import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Public key distribution for the fleet. The gateway fetches this once,
 * caches it, and verifies every incoming JWT locally — auth is not on
 * the hot path of ordinary requests.
 */
@RestController
public class JwksController {

    private final RSAKey signingKey;

    public JwksController(RSAKey signingKey) {
        this.signingKey = signingKey;
    }

    @GetMapping("/.well-known/jwks.json")
    @Operation(summary = "Public JSON Web Key Set used to verify access tokens")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(15, TimeUnit.MINUTES).cachePublic())
            .body(Map.of("keys", java.util.List.of(signingKey.toPublicJWK().toJSONObject())));
    }
}
