package dev.bob.openmarket.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bob.openmarket.auth.common.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Stateless security: every request carries its own proof (the JWT).
 *
 * <p>Public surface: service info/health, JWKS, swagger, and the
 * register/login/refresh endpoints. Everything else requires a valid
 * access token (header first, cookie fallback) — see {@code TokenCookieService}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // @PreAuthorize on the admin endpoints
public class SecurityConfig {

    private final ObjectMapper mapper;

    public SecurityConfig(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** owner ⊃ admin ⊃ moderator — the JWT carries role ids. */
    @Bean
    public org.springframework.security.access.hierarchicalroles.RoleHierarchy roleHierarchy() {
        return org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl
            .fromHierarchy("ROLE_OWNER > ROLE_ADMIN\nROLE_ADMIN > ROLE_MODERATOR");
    }

    /** Hook the hierarchy into @PreAuthorize (method security). */
    @Bean
    public static org.springframework.security.access.expression.method.MethodSecurityExpressionHandler
        methodSecurityExpressionHandler(
            org.springframework.security.access.hierarchicalroles.RoleHierarchy hierarchy) {
        var handler = new org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(hierarchy);
        return handler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder,
                                           dev.bob.openmarket.auth.token.TokenCookieService tokenCookieService) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/health/live", "/health/ready").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/.well-known/jwks.json").permitAll()
                .requestMatchers("/docs", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                // logout must work even with an expired access token — the
                // refresh cookie is proof enough, and the endpoint is
                // best-effort by design (always clears cookies)
                .requestMatchers("/api/v1/auth/logout").permitAll()
                // OAuth: the browser hits these mid-redirect, possibly anonymous
                .requestMatchers("/api/v1/auth/discord", "/api/v1/auth/discord/callback").permitAll()
                // email flows: the e-mailed token is the credential
                .requestMatchers("/api/v1/auth/verify-email", "/api/v1/auth/forgot-password",
                    "/api/v1/auth/reset-password").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
                .bearerTokenResolver(tokenCookieService)
                .jwt(jwt -> jwt.jwtAuthenticationConverter(roleGrantingConverter()))
                .authenticationEntryPoint((req, res, ex) -> writeJson(res, 401,
                    ApiError.of("unauthorized", "A valid access token is required")))
                .accessDeniedHandler((req, res, ex) -> writeJson(res, 403,
                    ApiError.of("forbidden", "Insufficient permissions"))));
        return http.build();
    }

    /**
     * Maps the {@code roles} claim (list of role ids) to ROLE_ authorities so
     * services can do {@code hasRole("admin")} later. Spring compares
     * authorities case-sensitively and upper-cases hasRole() arguments —
     * so the authority must be upper-case too.
     */
    private static JwtAuthenticationConverter roleGrantingConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::extractRoles);
        return converter;
    }

    private static List<GrantedAuthority> extractRoles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
            .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()))
            .collect(Collectors.toList());
    }

    private void writeJson(HttpServletResponse res, int status, ApiError error) {
        try {
            res.setStatus(status);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(res.getWriter(), error);
        } catch (Exception ignored) {
            // response already committed; nothing sensible left to do
        }
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
