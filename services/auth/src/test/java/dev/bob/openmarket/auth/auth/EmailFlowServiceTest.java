package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.common.RateLimiter;
import dev.bob.openmarket.auth.common.RateLimitException;
import dev.bob.openmarket.auth.common.UnauthorizedException;
import dev.bob.openmarket.auth.domain.Credential;
import dev.bob.openmarket.auth.domain.User;
import dev.bob.openmarket.auth.domain.VerificationToken;
import dev.bob.openmarket.auth.mail.EmailDispatcher;
import dev.bob.openmarket.auth.repository.CredentialRepository;
import dev.bob.openmarket.auth.repository.UserRepository;
import dev.bob.openmarket.auth.support.TestUsers;
import dev.bob.openmarket.auth.token.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The email-backed flows. Tokens captured via a mocked VerificationService;
 * the real token lifecycle has its own service test, and the flow test
 * completes these flows live by grepping the dev mail log.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailFlowServiceTest {

    @Mock UserRepository users;
    @Mock CredentialRepository credentials;
    @Mock VerificationService verifications;
    @Mock RefreshTokenService refreshTokens;
    @Mock EmailDispatcher email;
    @Mock RateLimiter rateLimiter;

    private EmailFlowService service;

    @BeforeEach
    void setUp() {
        service = new EmailFlowService(users, credentials, verifications, refreshTokens,
            email, rateLimiter, new BCryptPasswordEncoder(10), "http://localhost:3000");
    }

    private User user(boolean verified) {
        User u = TestUsers.user();
        u.setEmailVerified(verified);
        return u;
    }

    private void stubUser(boolean verified) {
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID))
            .thenReturn(Optional.of(user(verified)));
    }

    private VerificationToken token(String type, String identifier) {
        VerificationToken t = new VerificationToken();
        t.setUserId(TestUsers.USER_ID);
        t.setType(type);
        t.setIdentifier(identifier);
        ReflectionTestUtils.setField(t, "createdAt", Instant.now());
        return t;
    }

    // ── verify email ─────────────────────────────────────────

    @Test
    void resend_sends_link_with_token_and_marks_nothing() {
        stubUser(false);
        when(verifications.issue(TestUsers.USER_ID, VerificationService.TYPE_EMAIL_VERIFY,
            "garen@demaciabook.com")).thenReturn("raw-token");

        service.resendVerification(TestUsers.USER_ID, "1.2.3.4");

        verify(email).send(eq("garen@demaciabook.com"), contains("confirm"),
            contains("/verify-email?token=raw-token"));
    }

    @Test
    void resend_on_verified_email_conflicts() {
        stubUser(true);

        assertThatThrownBy(() -> service.resendVerification(TestUsers.USER_ID, null))
            .isInstanceOf(dev.bob.openmarket.auth.common.ConflictException.class)
            .hasFieldOrPropertyWithValue("code", "email_already_verified");
    }

    @Test
    void verifyEmail_sets_verified_flag() {
        when(verifications.consume(eq("raw"), eq(VerificationService.TYPE_EMAIL_VERIFY), any()))
            .thenReturn(token(VerificationService.TYPE_EMAIL_VERIFY, "garen@demaciabook.com"));
        User u = user(false);
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID)).thenReturn(Optional.of(u));

        service.verifyEmail("raw");

        assertThat(u.isEmailVerified()).isTrue();
    }

    @Test
    void verifyEmail_with_change_token_swaps_the_email() {
        when(verifications.consume(eq("ct"), any(), any()))
            .thenReturn(token(VerificationService.TYPE_EMAIL_CHANGE, "lux2@demaciabook.com"));
        User u = user(true);
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID)).thenReturn(Optional.of(u));

        service.verifyEmail("ct");

        assertThat(u.getEmail()).isEqualTo("lux2@demaciabook.com");
        assertThat(u.isEmailVerified()).isTrue();
    }

    @Test
    void verifyEmail_with_change_token_for_an_address_claimed_in_the_meantime_conflicts() {
        when(verifications.consume(eq("ct"), any(), any()))
            .thenReturn(token(VerificationService.TYPE_EMAIL_CHANGE, "lux2@demaciabook.com"));
        User u = user(true);
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID)).thenReturn(Optional.of(u));
        // someone registered the address while the link sat in the inbox
        when(users.existsByEmail("lux2@demaciabook.com")).thenReturn(true);

        assertThatThrownBy(() -> service.verifyEmail("ct"))
            .isInstanceOfSatisfying(dev.bob.openmarket.auth.common.ConflictException.class,
                e -> assertThat(e.code()).isEqualTo("email_taken"));
        assertThat(u.getEmail()).isEqualTo("garen@demaciabook.com"); // unchanged
    }

    @Test
    void verifyEmail_for_deleted_user_is_invalid_token() {
        when(verifications.consume(eq("raw"), any(), any()))
            .thenReturn(token(VerificationService.TYPE_EMAIL_VERIFY, "garen@demaciabook.com"));
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyEmail("raw"))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("invalid_token"));
    }

    @Test
    void verifyEmail_with_garbage_token_is_401_invalid_token() {
        when(verifications.consume(anyString(), any(), any()))
            .thenThrow(new UnauthorizedException("invalid_token", "Unknown or already used token"));

        assertThatThrownBy(() -> service.verifyEmail("nope"))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("invalid_token"));
    }

    // ── change email ─────────────────────────────────────────

    @Test
    void emailChange_sends_confirmation_to_the_new_address() {
        stubUser(true);
        when(users.existsByEmail("lux2@demaciabook.com")).thenReturn(false);
        when(verifications.issue(TestUsers.USER_ID, VerificationService.TYPE_EMAIL_CHANGE,
            "lux2@demaciabook.com")).thenReturn("change-token");

        service.requestEmailChange(TestUsers.USER_ID, "Lux2@DemaciaBook.com", null);

        verify(email).send(eq("lux2@demaciabook.com"), contains("new email"),
            contains("/verify-email?token=change-token"));
    }

    @Test
    void emailChange_to_taken_address_conflicts() {
        stubUser(true);
        when(users.existsByEmail("lux2@demaciabook.com")).thenReturn(true);

        assertThatThrownBy(() -> service.requestEmailChange(TestUsers.USER_ID, "lux2@demaciabook.com", null))
            .isInstanceOfSatisfying(dev.bob.openmarket.auth.common.ConflictException.class,
                e -> assertThat(e.code()).isEqualTo("email_taken"));
    }

    @Test
    void emailChange_to_same_address_on_verified_account_conflicts() {
        stubUser(true);

        assertThatThrownBy(() -> service.requestEmailChange(
            TestUsers.USER_ID, "garen@demaciabook.com", null))
            .isInstanceOfSatisfying(dev.bob.openmarket.auth.common.ConflictException.class,
                e -> assertThat(e.code()).isEqualTo("email_already_verified"));
    }

    // ── forgot / reset ───────────────────────────────────────

    @Test
    void forgotPassword_for_known_user_sends_reset_link() {
        when(users.findByEmail("garen@demaciabook.com"))
            .thenReturn(Optional.of(user(true)));
        when(verifications.issue(TestUsers.USER_ID, VerificationService.TYPE_PASSWORD_RESET,
            "garen@demaciabook.com")).thenReturn("reset-token");

        service.forgotPassword("garen@demaciabook.com", "1.2.3.4");

        verify(email).send(eq("garen@demaciabook.com"), contains("reset"),
            contains("/reset-password?token=reset-token"));
    }

    @Test
    void forgotPassword_send_failure_is_swallowed_to_stay_enumeration_safe() {
        when(users.findByEmail("garen@demaciabook.com"))
            .thenReturn(Optional.of(user(true)));
        when(verifications.issue(TestUsers.USER_ID, VerificationService.TYPE_PASSWORD_RESET,
            "garen@demaciabook.com")).thenReturn("reset-token");
        doThrow(new IllegalStateException("Email delivery failed"))
            .when(email).send(anyString(), anyString(), anyString());

        // the always-204 contract wins: a dead relay must not behave
        // differently for real vs. fake addresses
        assertThatCode(() -> service.forgotPassword("garen@demaciabook.com", "1.2.3.4"))
            .doesNotThrowAnyException();

        // issuance happened before the send — the token is out and consumable
        verify(verifications).issue(TestUsers.USER_ID, VerificationService.TYPE_PASSWORD_RESET,
            "garen@demaciabook.com");
    }

    @Test
    void forgotPassword_is_silent_for_unknown_emails() {
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatCode(() -> service.forgotPassword("sylas@mage-underground.org", null))
            .doesNotThrowAnyException();
        verify(email, org.mockito.Mockito.never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void resetPassword_updates_password_and_revokes_everything() {
        when(verifications.consume(eq("reset-token"), eq(VerificationService.TYPE_PASSWORD_RESET)))
            .thenReturn(token(VerificationService.TYPE_PASSWORD_RESET, "garen@demaciabook.com"));
        when(users.findById(TestUsers.USER_ID)).thenReturn(Optional.of(user(true)));
        when(credentials.findById(TestUsers.USER_ID)).thenReturn(Optional.empty());
        when(credentials.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resetPassword("reset-token", "theNewPass123");

        var c = org.mockito.ArgumentCaptor.forClass(Credential.class);
        verify(credentials).save(c.capture());
        assertThat(c.getValue().getPasswordHash()).startsWith("$2");
        verify(refreshTokens).revokeAllForUser(TestUsers.USER_ID);
    }

    @Test
    void resetPassword_for_deleted_user_is_invalid_token() {
        when(verifications.consume(eq("reset-token"), eq(VerificationService.TYPE_PASSWORD_RESET)))
            .thenReturn(token(VerificationService.TYPE_PASSWORD_RESET, "garen@demaciabook.com"));
        User deleted = user(true);
        deleted.setDeletedAt(Instant.now());
        when(users.findById(TestUsers.USER_ID)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.resetPassword("reset-token", "theNewPass123"))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("invalid_token"));
    }
}
