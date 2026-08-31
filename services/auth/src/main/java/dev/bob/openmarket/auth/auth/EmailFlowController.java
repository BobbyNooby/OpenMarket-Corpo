package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.auth.dto.EmailChangeRequest;
import dev.bob.openmarket.auth.auth.dto.ForgotPasswordRequest;
import dev.bob.openmarket.auth.auth.dto.ResetPasswordRequest;
import dev.bob.openmarket.auth.auth.dto.VerifyEmailRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Email-backed flows (Phase D). Confirm endpoints are anonymous — the
 * e-mailed token IS the credential. Always-204 forgot-password is deliberate:
 * no user enumeration.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth")
public class EmailFlowController {

    private final EmailFlowService emailFlows;

    public EmailFlowController(EmailFlowService emailFlows) {
        this.emailFlows = emailFlows;
    }

    @PostMapping("/verify-email/resend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Re-send the email verification link")
    public void resend(Authentication authentication, HttpServletRequest http) {
        emailFlows.resendVerification(UUID.fromString(authentication.getName()), ip(http));
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Confirm email verification or an email change with the e-mailed token")
    public void verify(@Valid @RequestBody VerifyEmailRequest request) {
        emailFlows.verifyEmail(request.token());
    }

    @PostMapping("/email/change")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Start an email change; confirmation link goes to the NEW address")
    public Map<String, String> change(@Valid @RequestBody EmailChangeRequest request,
                                      Authentication authentication, HttpServletRequest http) {
        emailFlows.requestEmailChange(UUID.fromString(authentication.getName()), request.newEmail(), ip(http));
        return Map.of("status", "verification_sent");
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Request a password reset (always 204, never reveals existence)")
    public void forgot(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
        emailFlows.forgotPassword(request.email(), ip(http));
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set a new password with the e-mailed token (revokes all sessions)")
    public void reset(@Valid @RequestBody ResetPasswordRequest request) {
        emailFlows.resetPassword(request.token(), request.newPassword());
    }

    private static String ip(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank()
            ? forwarded.split(",")[0].trim()
            : http.getRemoteAddr();
    }
}
