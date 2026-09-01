package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.common.UnauthorizedException;
import dev.bob.openmarket.auth.domain.VerificationToken;
import dev.bob.openmarket.auth.repository.VerificationTokenRepository;
import dev.bob.openmarket.auth.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Single-use verification token lifecycle: issue hashing + per-type expiry
 * windows, and the atomic consume. Flow wiring (which endpoint expects which
 * token type) lives in EmailFlowServiceTest.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VerificationServiceTest {

    private static final UUID TOKEN_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock VerificationTokenRepository repository;

    private VerificationService service;

    @BeforeEach
    void setUp() {
        service = new VerificationService(repository);
        ReflectionTestUtils.setField(service, "emailVerifyHours", 24L);
        ReflectionTestUtils.setField(service, "passwordResetMinutes", 60L);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private VerificationToken token(String type, Instant expiresAt, Instant usedAt) {
        VerificationToken t = new VerificationToken();
        ReflectionTestUtils.setField(t, "id", TOKEN_ID);
        t.setUserId(TestUsers.USER_ID);
        t.setType(type);
        t.setIdentifier("garen@demaciabook.com");
        t.setTokenHash("stored-hash");
        t.setExpiresAt(expiresAt);
        t.setUsedAt(usedAt);
        return t;
    }

    private VerificationToken unusedToken(String type) {
        return token(type, Instant.now().plusSeconds(600), null);
    }

    // ── issue ────────────────────────────────────────────────

    @Test
    void issue_email_verify_stores_only_the_hash_and_lives_24h() {
        String raw = service.issue(TestUsers.USER_ID, VerificationService.TYPE_EMAIL_VERIFY,
            "garen@demaciabook.com");

        assertThat(raw).matches("^[A-Za-z0-9_-]{43}$"); // 32 random bytes, base64url
        var saved = savedToken();
        assertThat(saved.getType()).isEqualTo(VerificationService.TYPE_EMAIL_VERIFY);
        assertThat(saved.getTokenHash()).hasSize(64).isNotEqualTo(raw); // hash, never the raw token
        assertThat(saved.getExpiresAt()).isAfter(Instant.now().plusSeconds(23 * 3600)); // ~24 h
    }

    @Test
    void issue_password_reset_uses_the_shorter_window() {
        service.issue(TestUsers.USER_ID, VerificationService.TYPE_PASSWORD_RESET,
            "garen@demaciabook.com");

        assertThat(savedToken().getExpiresAt()).isAfter(Instant.now().plusSeconds(55 * 60)); // ~60 min
    }

    @Test
    void issue_supersedes_outstanding_tokens_of_the_same_type() {
        service.issue(TestUsers.USER_ID, VerificationService.TYPE_EMAIL_VERIFY, "garen@demaciabook.com");

        verify(repository).supersedeAllForUser(eq(TestUsers.USER_ID),
            eq(VerificationService.TYPE_EMAIL_VERIFY), any());
    }

    @Test
    void reissue_supersedes_only_its_own_type() {
        service.issue(TestUsers.USER_ID, VerificationService.TYPE_EMAIL_VERIFY, "garen@demaciabook.com");
        service.issue(TestUsers.USER_ID, VerificationService.TYPE_PASSWORD_RESET, "garen@demaciabook.com");

        var types = ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).supersedeAllForUser(eq(TestUsers.USER_ID), types.capture(), any());
        assertThat(types.getAllValues())
            .containsExactly(VerificationService.TYPE_EMAIL_VERIFY, VerificationService.TYPE_PASSWORD_RESET);
    }

    @Test
    void token_superseded_by_a_reissue_fails_consume() {
        // what the bulk UPDATE leaves behind for the superseded link: used_at set
        VerificationToken superseded = token(VerificationService.TYPE_EMAIL_VERIFY,
            Instant.now().plusSeconds(600), Instant.now());
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(superseded));

        assertThatThrownBy(() -> service.consume("old-raw", VerificationService.TYPE_EMAIL_VERIFY))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("invalid_token"));

        verify(repository, never()).consume(any(), any());
    }

    // ── consume ──────────────────────────────────────────────

    @Test
    void consume_marks_the_row_used_atomically_and_returns_it() {
        VerificationToken t = unusedToken(VerificationService.TYPE_EMAIL_VERIFY);
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(t));
        when(repository.consume(eq(TOKEN_ID), any())).thenReturn(1); // this submit wins the race

        VerificationToken consumed = service.consume("raw", VerificationService.TYPE_EMAIL_VERIFY);

        assertThat(consumed.getUserId()).isEqualTo(TestUsers.USER_ID);
        assertThat(consumed.getUsedAt()).isNotNull(); // mirrored onto the entity for the caller
        verify(repository).consume(eq(TOKEN_ID), any());
    }

    @Test
    void consume_losing_the_consume_race_is_invalid_token() {
        VerificationToken t = unusedToken(VerificationService.TYPE_EMAIL_VERIFY);
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(t));
        when(repository.consume(eq(TOKEN_ID), any())).thenReturn(0); // a concurrent submit won

        assertThatThrownBy(() -> service.consume("raw", VerificationService.TYPE_EMAIL_VERIFY))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("invalid_token"));
    }

    @Test
    void consume_already_used_token_is_invalid_without_touching_the_row() {
        VerificationToken t = token(VerificationService.TYPE_EMAIL_VERIFY,
            Instant.now().plusSeconds(600), Instant.now().minusSeconds(10));
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.consume("raw", VerificationService.TYPE_EMAIL_VERIFY))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("invalid_token"));

        verify(repository, never()).consume(any(), any());
    }

    @Test
    void consume_expired_token_is_token_expired() {
        VerificationToken t = token(VerificationService.TYPE_EMAIL_VERIFY,
            Instant.now().minusSeconds(10), null);
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.consume("raw", VerificationService.TYPE_EMAIL_VERIFY))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("token_expired"));

        verify(repository, never()).consume(any(), any());
    }

    @Test
    void consume_wrong_type_is_invalid_for_this_action() {
        when(repository.findByTokenHash(anyString()))
            .thenReturn(Optional.of(unusedToken(VerificationService.TYPE_EMAIL_VERIFY)));

        assertThatThrownBy(() -> service.consume("raw", VerificationService.TYPE_PASSWORD_RESET))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("invalid_token"));

        verify(repository, never()).consume(any(), any());
    }

    @Test
    void consume_missing_raw_token_is_invalid() {
        assertThatThrownBy(() -> service.consume(null, VerificationService.TYPE_EMAIL_VERIFY))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("invalid_token"));

        verify(repository, never()).findByTokenHash(anyString());
    }

    // ── capture helpers ──────────────────────────────────────

    private VerificationToken savedToken() {
        var captor = ArgumentCaptor.forClass(VerificationToken.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
