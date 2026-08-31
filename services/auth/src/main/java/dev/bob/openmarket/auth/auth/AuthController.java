package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.auth.dto.AuthResponse;
import dev.bob.openmarket.auth.auth.dto.LoginRequest;
import dev.bob.openmarket.auth.auth.dto.RegisterRequest;
import dev.bob.openmarket.auth.token.AuthResult;
import dev.bob.openmarket.auth.token.TokenCookieService;
import dev.bob.openmarket.auth.user.dto.UserResponse;
import dev.bob.openmarket.auth.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cookie-based auth flows. Success sets om_access + om_refresh httpOnly
 * cookies; failures return the standard error envelope.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final TokenCookieService cookies;

    public AuthController(AuthService authService, UserService userService, TokenCookieService cookies) {
        this.authService = authService;
        this.userService = userService;
        this.cookies = cookies;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account (logs you in)")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request,
                                 HttpServletRequest http, HttpServletResponse res) {
        AuthResult result = authService.register(request, userAgent(http), ip(http));
        cookies.write(res, result.accessToken(), result.refreshToken());
        return new AuthResponse(UserResponse.from(result.user(), authService.rolesOf(result.user())));
    }

    @PostMapping("/login")
    @Operation(summary = "Log in with email + password")
    public AuthResponse login(@Valid @RequestBody LoginRequest request,
                              HttpServletRequest http, HttpServletResponse res) {
        AuthResult result = authService.login(request, userAgent(http), ip(http));
        cookies.write(res, result.accessToken(), result.refreshToken());
        return new AuthResponse(UserResponse.from(result.user(), authService.rolesOf(result.user())));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange the refresh cookie for a fresh token pair (rotation)")
    public AuthResponse refresh(HttpServletRequest http, HttpServletResponse res) {
        AuthResult result = authService.refresh(cookies.refreshFrom(http), userAgent(http), ip(http));
        cookies.write(res, result.accessToken(), result.refreshToken());
        return new AuthResponse(UserResponse.from(result.user(), authService.rolesOf(result.user())));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the refresh cookie and clear both cookies")
    public void logout(HttpServletRequest http, HttpServletResponse res) {
        authService.logout(cookies.refreshFrom(http));
        cookies.clear(res);
    }

    private static String userAgent(HttpServletRequest http) {
        return http.getHeader("User-Agent");
    }

    private static String ip(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank()
            ? forwarded.split(",")[0].trim()
            : http.getRemoteAddr();
    }
}
