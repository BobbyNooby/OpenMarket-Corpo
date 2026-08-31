package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.auth.dto.ChangePasswordRequest;
import dev.bob.openmarket.auth.auth.dto.RemovePasswordRequest;
import dev.bob.openmarket.auth.auth.dto.SetPasswordRequest;
import dev.bob.openmarket.auth.token.RefreshTokenService;
import dev.bob.openmarket.auth.token.TokenCookieService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Password-credential lifecycle: add (Discord-first users), change
 * (revokes other devices), remove (only while another login method exists).
 */
@RestController
@RequestMapping("/api/v1/auth/credentials")
@Tag(name = "auth")
public class CredentialController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokens;
    private final TokenCookieService cookies;

    public CredentialController(AuthService authService,
                                RefreshTokenService refreshTokens,
                                TokenCookieService cookies) {
        this.authService = authService;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a password (accounts that signed up via OAuth)")
    public void add(@Valid @RequestBody SetPasswordRequest request, Authentication authentication) {
        authService.addPassword(currentUserId(authentication), request.password());
    }

    @PatchMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Change password (revokes all other device sessions)")
    public void change(@Valid @RequestBody ChangePasswordRequest request,
                       Authentication authentication, HttpServletRequest http) {
        UUID userId = currentUserId(authentication);
        UUID keepFamily = refreshTokens.familyOf(cookies.refreshFrom(http));
        authService.changePassword(userId, request.currentPassword(), request.newPassword(), keepFamily);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove the password (requires another linked login method)")
    public void remove(@Valid @RequestBody RemovePasswordRequest request, Authentication authentication) {
        authService.removePassword(currentUserId(authentication), request.currentPassword());
    }

    private static UUID currentUserId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
