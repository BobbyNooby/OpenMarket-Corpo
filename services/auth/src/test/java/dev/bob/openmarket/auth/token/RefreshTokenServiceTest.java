package dev.bob.openmarket.auth.token;

import dev.bob.openmarket.auth.common.UnauthorizedException;
import dev.bob.openmarket.auth.config.JwtProperties;
import dev.bob.openmarket.auth.domain.RefreshToken;
import dev.bob.openmarket.auth.repository.RefreshTokenRepository;
import dev.bob.openmarket.auth.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Decision logic of the refresh-token lifecycle. NOT covered here (needs a
 * real DB + transaction manager, i.e. Testcontainers): whether the family
 * revocation actually survives the rollback on reuse.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefreshTokenServiceTest {

    private static final UUID FAMILY = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock RefreshTokenRepository repository;
    @Mock PlatformTransactionManager txManager;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new RefreshTokenService(repository, new JwtProperties(), txManager);
    }

    private RefreshToken token(Instant expiresAt, Instant revokedAt) {
        RefreshToken t = new RefreshToken();
        t.setUserId(TestUsers.USER_ID);
        t.setFamilyId(FAMILY);
        t.setTokenHash("stored-hash");
        t.setUserAgent("LeagueClient/24.0");
        t.setIpAddress("127.0.0.1");
        t.setExpiresAt(expiresAt);
        t.setRevokedAt(revokedAt);
        return t;
    }

    private RefreshToken active() {
        return token(Instant.now().plusSeconds(3600), null);
    }

    // ── issue ────────────────────────────────────────────────

    @Test
    void issue_starts_new_family_with_full_ttl_and_stores_only_the_hash() {
        String raw = service.issue(TestUsers.USER_ID, "RiotClient/24.0", "1.2.3.4");

        assertThat(raw).matches("^[A-Za-z0-9_-]{43}$"); // 32 random bytes, base64url

        var saved = repositoryFound();
        assertThat(saved.getFamilyId()).isNotNull();
        assertThat(saved.getRotatedFromId()).isNull();
        assertThat(saved.getTokenHash()).hasSize(64).isNotEqualTo(raw); // hash, never the raw token
        assertThat(saved.getUserAgent()).isEqualTo("RiotClient/24.0");
        assertThat(saved.getIpAddress()).isEqualTo("1.2.3.4");
        assertThat(saved.getExpiresAt()).isAfter(Instant.now().plusSeconds(6 * 24 * 3600)); // ~7 days
    }

    // ── rotate ───────────────────────────────────────────────

    @Test
    void rotate_consumes_old_and_issues_successor_in_same_family() {
        RefreshToken old = active();
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(old));
        when(repository.consume(any(), any())).thenReturn(1); // this refresher wins the race

        RefreshTokenService.Rotated rotated = service.rotate("raw");

        assertThat(old.getRevokedAt()).isNotNull();                 // consumed
        assertThat(rotated.entity().getFamilyId()).isEqualTo(FAMILY); // same family
        assertThat(rotated.entity().getRotatedFromId()).isEqualTo(old.getId());
        assertThat(rotated.entity().getTokenHash()).isNotEqualTo(old.getTokenHash());
        assertThat(rotated.entity().getUserAgent()).isEqualTo("LeagueClient/24.0"); // device inherited
        assertThat(rotated.rawToken()).isNotBlank();
    }

    @Test
    void rotate_losing_the_consume_race_is_treated_as_reuse() {
        RefreshToken old = active();
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(old));
        when(repository.consume(any(), any())).thenReturn(0); // a concurrent refresher already won

        assertThatThrownBy(() -> service.rotate("raw"))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("refresh_token_reused"));

        verify(repository).revokeActiveInFamily(eq(FAMILY), any()); // family revoked, incl. the winner's successor
        verify(repository, never()).save(any());
    }

    @Test
    void rotate_reused_token_throws_refresh_token_reused_and_revokes_family() {
        RefreshToken consumed = token(Instant.now().plusSeconds(3600), Instant.now().minusSeconds(10));
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(consumed));

        assertThatThrownBy(() -> service.rotate("raw"))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("refresh_token_reused"));

        verify(repository).revokeActiveInFamily(eq(FAMILY), any());
    }

    @Test
    void rotate_expired_token_throws_without_family_revoke() {
        when(repository.findByTokenHash(anyString()))
            .thenReturn(Optional.of(token(Instant.now().minusSeconds(10), null)));

        assertThatThrownBy(() -> service.rotate("raw"))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("refresh_token_expired"));

        verify(repository, never()).revokeActiveInFamily(any(), any());
        verify(repository, never()).consume(any(), any());
    }

    @Test
    void rotate_unknown_token_throws_invalid() {
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("raw"))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("invalid_refresh_token"));
    }

    @Test
    void rotate_missing_token_throws_missing() {
        assertThatThrownBy(() -> service.rotate(null))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("missing_refresh_token"));
    }

    // ── revoke ───────────────────────────────────────────────

    @Test
    void revoke_consumes_the_token_atomically() {
        service.revoke("raw");

        verify(repository).revokeByTokenHash(anyString(), any()); // conditional UPDATE, no load-then-set
    }

    @Test
    void revokeAllForUser_is_one_bulk_update() {
        service.revokeAllForUser(TestUsers.USER_ID);

        verify(repository).revokeAllForUser(eq(TestUsers.USER_ID), any());
    }

    @Test
    void revokeFamilyForUser_reports_whether_something_was_revoked() {
        when(repository.revokeActiveInFamilyForUser(eq(FAMILY), eq(TestUsers.USER_ID), any())).thenReturn(1);
        assertThat(service.revokeFamilyForUser(TestUsers.USER_ID, FAMILY)).isTrue();

        // re-stub: last matching stubbing wins
        when(repository.revokeActiveInFamilyForUser(eq(FAMILY), eq(TestUsers.USER_ID), any())).thenReturn(0);
        assertThat(service.revokeFamilyForUser(TestUsers.USER_ID, FAMILY)).isFalse();
    }

    // ── familyOf ─────────────────────────────────────────────

    @Test
    void familyOf_resolves_family_or_null_silently() {
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(active()));
        assertThat(service.familyOf("raw")).isEqualTo(FAMILY);

        when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThat(service.familyOf("raw")).isNull();

        assertThat(service.familyOf(null)).isNull();
    }

    // ── listSessions ─────────────────────────────────────────

    @Test
    void listSessions_groups_by_family_and_flags_current() {
        Instant created = Instant.now().minusSeconds(60);
        RefreshToken t1 = active();  // family F
        ReflectionTestUtils.setField(t1, "createdAt", created);
        RefreshToken t2 = active();  // same family, newer rotation
        ReflectionTestUtils.setField(t2, "createdAt", created.plusSeconds(30));
        UUID otherFamily = UUID.fromString("44444444-4444-4444-4444-444444444444");
        RefreshToken t3 = active();
        t3.setFamilyId(otherFamily);
        ReflectionTestUtils.setField(t3, "createdAt", created.minusSeconds(3600));

        when(repository.findByUserIdAndRevokedAtIsNull(TestUsers.USER_ID)).thenReturn(List.of(t1, t2, t3));

        var sessions = service.listSessions(TestUsers.USER_ID, otherFamily);

        assertThat(sessions).hasSize(2);
        var current = sessions.stream().filter(RefreshTokenService.SessionView::current).findFirst().orElseThrow();
        assertThat(current.familyId()).isEqualTo(otherFamily);
        var newest = sessions.get(0); // sorted newest first
        assertThat(newest.familyId()).isEqualTo(FAMILY);
        assertThat(newest.expiresAt()).isEqualTo(t2.getExpiresAt()); // max of family
    }

    @Test
    void listSessions_skips_expired_rows() {
        RefreshToken expired = token(Instant.now().minusSeconds(1), null);
        when(repository.findByUserIdAndRevokedAtIsNull(TestUsers.USER_ID)).thenReturn(List.of(expired));

        assertThat(service.listSessions(TestUsers.USER_ID, null)).isEmpty();
    }

    private RefreshToken repositoryFound() {
        org.mockito.ArgumentCaptor<RefreshToken> captor = org.mockito.ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
