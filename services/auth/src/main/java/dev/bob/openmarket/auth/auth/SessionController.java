package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.auth.dto.SessionResponse;
import dev.bob.openmarket.auth.common.NotFoundException;
import dev.bob.openmarket.auth.token.RefreshTokenService;
import dev.bob.openmarket.auth.token.TokenCookieService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * "Where am I logged in?" — one row per refresh-token family (= one device
 * session). The presented om_refresh cookie marks which row is `current`.
 */
@RestController
@RequestMapping("/api/v1/auth/sessions")
@Tag(name = "auth")
public class SessionController {

    private final RefreshTokenService refreshTokens;
    private final TokenCookieService cookies;

    public SessionController(RefreshTokenService refreshTokens, TokenCookieService cookies) {
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
    }

    @GetMapping
    @Operation(summary = "List your live sessions (newest first)")
    public List<SessionResponse> sessions(Authentication authentication, HttpServletRequest http) {
        UUID userId = UUID.fromString(authentication.getName());
        UUID currentFamily = refreshTokens.familyOf(cookies.refreshFrom(http));
        return refreshTokens.listSessions(userId, currentFamily).stream()
            .map(s -> new SessionResponse(s.familyId(), s.userAgent(), s.ipAddress(),
                s.createdAt(), s.expiresAt(), s.current()))
            .toList();
    }

    @DeleteMapping("/{familyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke one device session")
    public void revoke(@PathVariable UUID familyId, Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        if (!refreshTokens.revokeFamilyForUser(userId, familyId)) {
            throw new NotFoundException("session_not_found", "No active session with that id");
        }
    }

    @PostMapping("/revoke-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke every session incl. this one (log out everywhere)")
    public void revokeAll(Authentication authentication) {
        refreshTokens.revokeAllForUser(UUID.fromString(authentication.getName()));
    }
}
