package dev.bob.openmarket.auth.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;

/**
 * Stands in for the real RS256 {@code JwtKeyConfig#jwtDecoder}: whatever
 * opaque token string the request carries becomes a valid Jwt for a fixed
 * identity. Roles come from the token suffix: "test-token" → ["user"],
 * "test-token:admin,owner" → ["admin","owner"]. Contract tests then prove
 * what the *filter chain and controllers* do with a valid/missing/
 * under-privileged token — not the cryptography (exercised in the flow test).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfig {

    public static final String ANY_TOKEN = "test-token";

    public static String tokenWithRoles(String... roles) {
        return ANY_TOKEN + ":" + String.join(",", roles);
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .subject(TestUsers.USER_ID.toString())
            .claim("roles", rolesOf(token))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(900))
            .build();
    }

    private static List<String> rolesOf(String token) {
        int idx = token.indexOf(':');
        if (idx < 0) {
            return List.of("user");
        }
        return List.of(token.substring(idx + 1).split(","));
    }
}
