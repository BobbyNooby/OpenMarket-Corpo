package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.common.ConflictException;
import dev.bob.openmarket.auth.common.NotFoundException;
import dev.bob.openmarket.auth.common.RateLimiter;
import dev.bob.openmarket.auth.common.UnauthorizedException;
import dev.bob.openmarket.auth.domain.Credential;
import dev.bob.openmarket.auth.domain.User;
import dev.bob.openmarket.auth.mail.EmailDispatcher;
import dev.bob.openmarket.auth.repository.CredentialRepository;
import dev.bob.openmarket.auth.repository.UserRepository;
import dev.bob.openmarket.auth.token.RefreshTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/**
 * The email-backed flows: verify, change email, forgot/reset password.
 * Token discipline mirrors refresh tokens — raw value only ever exists in
 * the emailed link, DB stores SHA-256 hashes, single use.
 */
@Service
public class EmailFlowService {

    private static final Logger log = LoggerFactory.getLogger(EmailFlowService.class);

    private final UserRepository users;
    private final CredentialRepository credentials;
    private final VerificationService verifications;
    private final RefreshTokenService refreshTokens;
    private final EmailDispatcher email;
    private final RateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final String appUrl;

    public EmailFlowService(UserRepository users,
                            CredentialRepository credentials,
                            VerificationService verifications,
                            RefreshTokenService refreshTokens,
                            EmailDispatcher email,
                            RateLimiter rateLimiter,
                            PasswordEncoder passwordEncoder,
                            @Value("${auth.app-url:http://localhost:3000}") String appUrl) {
        this.users = users;
        this.credentials = credentials;
        this.verifications = verifications;
        this.refreshTokens = refreshTokens;
        this.email = email;
        this.rateLimiter = rateLimiter;
        this.passwordEncoder = passwordEncoder;
        this.appUrl = appUrl;
    }

    // ── verify email ─────────────────────────────────────────

    @Transactional
    public void resendVerification(UUID userId, String ip) {
        rateLimiter.allow("verify_resend", userId.toString(), 3, Duration.ofHours(1));
        User user = users.findByIdAndDeletedAtIsNull(userId)
            .orElseThrow(() -> new NotFoundException("user_not_found", "User not found"));
        if (user.isEmailVerified()) {
            throw new ConflictException("email_already_verified", "Email is already verified", null);
        }
        String raw = verifications.issue(user.getId(), VerificationService.TYPE_EMAIL_VERIFY, user.getEmail());
        email.send(user.getEmail(), "OpenMarket — confirm your email",
            "Welcome to OpenMarket!\n\nConfirm your email:\n" + appUrl
                + "/verify-email?token=" + raw
                + "\n\nIf you didn't create this account, ignore this message.");
    }

    /**
     * The one confirm endpoint for BOTH e-mailed confirmations: signup
     * verification (email_verify) and email change (email_change — swaps
     * `users.email` to the address the token carries).
     */
    @Transactional
    public void verifyEmail(String rawToken) {
        var token = verifications.consume(rawToken,
            VerificationService.TYPE_EMAIL_VERIFY, VerificationService.TYPE_EMAIL_CHANGE);
        // deletedAt-is-null-aware: a soft-deleted account must not be able to
        // consume a token (same guard as resetPassword below).
        User user = users.findByIdAndDeletedAtIsNull(token.getUserId())
            .orElseThrow(() -> new UnauthorizedException("invalid_token", "Unknown or already used token"));
        if (VerificationService.TYPE_EMAIL_CHANGE.equals(token.getType())) {
            // the token's identifier IS the proof of control over the new address —
            // but the link can outlive the moment it was issued, so re-check it
            // against live users right before applying (residual race after this
            // check is caught by the unique constraint → global 409 handler).
            if (users.existsByEmail(token.getIdentifier())) {
                throw new ConflictException("email_taken", "An account with this email already exists", "email");
            }
            user.setEmail(token.getIdentifier());
        }
        user.setEmailVerified(true);
    }

    // ── change email (verified via the same confirm endpoint) ─

    @Transactional
    public void requestEmailChange(UUID userId, String newEmail, String ip) {
        rateLimiter.allow("email_change", userId.toString(), 5, Duration.ofHours(1));
        User user = users.findByIdAndDeletedAtIsNull(userId)
            .orElseThrow(() -> new NotFoundException("user_not_found", "User not found"));
        String target = newEmail.trim().toLowerCase();

        if (target.equals(user.getEmail())) {
            // "change" to the same address == (re)send the verification mail
            if (user.isEmailVerified()) {
                throw new ConflictException("email_already_verified", "Email is already verified", null);
            }
            String raw = verifications.issue(user.getId(), VerificationService.TYPE_EMAIL_VERIFY, target);
            email.send(target, "OpenMarket — confirm your email",
                "Confirm your email:\n" + appUrl + "/verify-email?token=" + raw);
            return;
        }

        if (users.existsByEmail(target)) {
            throw new ConflictException("email_taken", "An account with this email already exists", "newEmail");
        }
        String raw = verifications.issue(user.getId(), VerificationService.TYPE_EMAIL_CHANGE, target);
        // the mail goes to the NEW address — proof the person controls it
        email.send(target, "OpenMarket — confirm your new email",
            "Confirm the new email for your OpenMarket account:\n" + appUrl
                + "/verify-email?token=" + raw
                + "\n\nIf you didn't request this, ignore this message and your email stays unchanged.");
    }

    // ── forgot / reset password ──────────────────────────────

    /** Always quiet — a 200/204 must not reveal whether the account exists. */
    @Transactional
    public void forgotPassword(String emailRaw, String ip) {
        String emailAddr = emailRaw.trim().toLowerCase();
        rateLimiter.allow("forgot", emailAddr + "|" + ip, 5, Duration.ofHours(1));

        users.findByEmail(emailAddr).filter(u -> u.getDeletedAt() == null).ifPresent(user -> {
            String raw = verifications.issue(user.getId(), VerificationService.TYPE_PASSWORD_RESET, user.getEmail());
            try {
                email.send(user.getEmail(), "OpenMarket — reset your password",
                    "Reset your password (valid 60 minutes):\n" + appUrl
                        + "/reset-password?token=" + raw
                        + "\n\nIf you didn't request this, ignore this message.");
            } catch (Exception e) {
                // a dead relay must not turn known addresses into 500s while
                // unknown ones get 204 — that difference is an enumeration
                // oracle. The always-204 contract wins; delivery problems
                // surface in the logs/metrics instead.
                log.warn("forgot-password mail delivery failed to {}: {}",
                    user.getEmail(), e.getMessage());
            }
        });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        var token = verifications.consume(rawToken, VerificationService.TYPE_PASSWORD_RESET);
        User user = users.findById(token.getUserId())
            .filter(u -> u.getDeletedAt() == null)
            .orElseThrow(() -> new UnauthorizedException("invalid_token", "Unknown or already used token"));

        Credential credential = credentials.findById(user.getId()).orElse(null);
        String hash = passwordEncoder.encode(newPassword);
        if (credential == null) {
            // OAuth-only account recovering access: they get a password too —
            // the reset link proves control of the (verified) inbox.
            credentials.save(new Credential(user.getId(), hash));
        } else {
            credential.setPasswordHash(hash);
        }
        // a password reset kills every session, everywhere
        refreshTokens.revokeAllForUser(user.getId());
    }
}
