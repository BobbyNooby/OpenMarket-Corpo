package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.auth.dto.LoginRequest;
import dev.bob.openmarket.auth.auth.dto.RegisterRequest;
import dev.bob.openmarket.auth.common.ConflictException;
import dev.bob.openmarket.auth.common.RateLimiter;
import dev.bob.openmarket.auth.common.UnauthorizedException;
import dev.bob.openmarket.auth.domain.Credential;
import dev.bob.openmarket.auth.domain.RefreshToken;
import dev.bob.openmarket.auth.domain.User;
import dev.bob.openmarket.auth.domain.UserProfile;
import dev.bob.openmarket.auth.domain.UserRole;
import dev.bob.openmarket.auth.repository.BanRepository;
import dev.bob.openmarket.auth.repository.CredentialRepository;
import dev.bob.openmarket.auth.repository.OAuthAccountRepository;
import dev.bob.openmarket.auth.repository.UserProfileRepository;
import dev.bob.openmarket.auth.repository.UserRepository;
import dev.bob.openmarket.auth.repository.UserRoleRepository;
import dev.bob.openmarket.auth.support.TestUsers;
import dev.bob.openmarket.auth.token.JwtService;
import dev.bob.openmarket.auth.token.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Registration/login/refresh decision logic. Timing-equalization (dummy
 * hash) and DB constraints are only approximated here; end-to-end behaviour
 * is pinned by the contract tests + smoke test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final UUID FAMILY = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock UserRepository users;
    @Mock UserProfileRepository profiles;
    @Mock CredentialRepository credentials;
    @Mock OAuthAccountRepository oauthAccounts;
    @Mock UserRoleRepository userRoles;
    @Mock BanRepository bans;
    @Mock RefreshTokenService refreshTokens;
    @Mock JwtService jwt;

    private AuthService service;

    @BeforeEach
    void setUp() {
        // real bcrypt (strength 10 for speed) — the hashing behaviour matters
        PasswordEncoder encoder = new BCryptPasswordEncoder(10);
        service = new AuthService(users, profiles, credentials, oauthAccounts, userRoles, bans,
            refreshTokens, jwt, new RateLimiter(), encoder);
    }

    private void happyRegisterMocks() {
        when(userRoles.countLiveOwners()).thenReturn(1L); // platform already has an owner
        when(users.existsByEmail(anyString())).thenReturn(false);
        when(profiles.existsByUsername(anyString())).thenReturn(false);
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(credentials.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(profiles.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRoles.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRoles.findRoleIdsByUserId(any())).thenReturn(List.of("user"));
        when(jwt.issue(any(), anyList())).thenReturn("access-jwt");
        when(refreshTokens.issue(any(), any(), any())).thenReturn("refresh-raw");
    }

    // ── register ─────────────────────────────────────────────

    @Test
    void register_normalizes_email_hashes_password_assigns_default_role() {
        happyRegisterMocks();

        var result = service.register(
            new RegisterRequest("  Garen@DemaciaBook.COM ", "demaciaforever222", "Garen Crownguard", null),
            "LeagueClient/24.0", "1.2.3.4");

        assertThat(savedUser().getEmail()).isEqualTo("garen@demaciabook.com"); // trimmed + lowercased
        assertThat(savedCredential().getPasswordHash()).startsWith("$2").isNotEqualTo("demaciaforever222");
        assertThat(savedRole().getRoleId()).isEqualTo("user");

        var profile = savedProfile();
        assertThat(profile.getUsername()).matches("^[a-z0-9_-]{3,32}$");
        assertThat(profile.getLanguage()).isEqualTo("en");
        assertThat(profile.getNotificationPreferences()).isEqualTo("{}");

        assertThat(result.accessToken()).isEqualTo("access-jwt");
        assertThat(result.refreshToken()).isEqualTo("refresh-raw");
    }

    @Test
    void register_first_live_user_becomes_owner() {
        happyRegisterMocks();
        when(userRoles.countLiveOwners()).thenReturn(0L); // no owner (soft-deleted ones don't count)

        service.register(new RegisterRequest(
            "garen@demaciabook.com", "demaciaforever222", "Garen Crownguard", null), null, null);

        assertThat(savedRole().getRoleId()).isEqualTo("owner");
    }

    @Test
    void register_with_existing_live_owners_mints_no_owner() {
        happyRegisterMocks();
        when(userRoles.countLiveOwners()).thenReturn(2L);

        service.register(new RegisterRequest(
            "garen@demaciabook.com", "demaciaforever222", "Garen Crownguard", null), null, null);

        assertThat(savedRole().getRoleId()).isEqualTo("user");
    }

    @Test
    void register_email_unique_race_surfaces_as_email_taken_not_500() {
        happyRegisterMocks();
        when(users.save(any())).thenThrow(new DataIntegrityViolationException("uq_users_email"));

        assertThatThrownBy(() -> service.register(
            new RegisterRequest("garen@demaciabook.com", "demaciaforever222", "G", null), null, null))
            .isInstanceOfSatisfying(ConflictException.class, e -> {
                assertThat(e.code()).isEqualTo("email_taken");
                assertThat(e.field()).isEqualTo("email");
            });
    }

    @Test
    void register_username_unique_race_surfaces_as_username_taken_not_500() {
        happyRegisterMocks();
        when(profiles.save(any())).thenThrow(new DataIntegrityViolationException("uq_user_profiles_username"));

        assertThatThrownBy(() -> service.register(
            new RegisterRequest("garen@demaciabook.com", "demaciaforever222", "G", null), null, null))
            .isInstanceOfSatisfying(ConflictException.class, e -> {
                assertThat(e.code()).isEqualTo("username_taken");
                assertThat(e.field()).isEqualTo("username");
            });
    }

    @Test
    void register_duplicate_email_conflicts_with_field() {
        happyRegisterMocks();
        when(users.existsByEmail("garen@demaciabook.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(
            new RegisterRequest("garen@demaciabook.com", "demaciaforever222", "G", null), null, null))
            .isInstanceOfSatisfying(ConflictException.class, e -> {
                assertThat(e.code()).isEqualTo("email_taken");
                assertThat(e.field()).isEqualTo("email");
            });
    }

    @Test
    void register_duplicate_explicit_username_conflicts_with_field() {
        happyRegisterMocks();
        when(profiles.existsByUsername("chogath")).thenReturn(true);

        assertThatThrownBy(() -> service.register(
            new RegisterRequest("garen@demaciabook.com", "demaciaforever222", "B", "chogath"), null, null))
            .isInstanceOfSatisfying(ConflictException.class, e -> {
                assertThat(e.code()).isEqualTo("username_taken");
                assertThat(e.field()).isEqualTo("username");
            });
    }

    @Test
    void derived_username_is_sanitized_unique_and_bounded() {
        happyRegisterMocks();

        service.register(new RegisterRequest(
            "garen@demaciabook.com", "demaciaforever222",
            "Garen Crownguard The Might Of Demacia And The Holder Of An Extremely Long Regal Title", null),
            null, null);

        var profile = savedProfile();
        assertThat(profile.getUsername()).matches("^[a-z0-9_-]{3,32}$");
        assertThat(profile.getUsername().length()).isLessThanOrEqualTo(32);
    }

    // ── login ────────────────────────────────────────────────

    @Test
    void login_with_correct_password_issues_pair() {
        User user = TestUsers.user();
        when(users.findByEmail("garen@demaciabook.com")).thenReturn(Optional.of(user));
        String hash = new BCryptPasswordEncoder(10).encode("demaciaforever222");
        when(credentials.findById(TestUsers.USER_ID))
            .thenReturn(Optional.of(new Credential(TestUsers.USER_ID, hash)));
        when(userRoles.findRoleIdsByUserId(TestUsers.USER_ID)).thenReturn(List.of("user"));
        when(jwt.issue(eq(TestUsers.USER_ID), eq(List.of("user")))).thenReturn("access-jwt");
        when(refreshTokens.issue(any(), any(), any())).thenReturn("refresh-raw");

        var result = service.login(new LoginRequest("garen@demaciabook.com", "demaciaforever222"), null, null);

        assertThat(result.accessToken()).isEqualTo("access-jwt");
        assertThat(result.refreshToken()).isEqualTo("refresh-raw");
    }

    @Test
    void login_unknown_email_is_indistinguishable_from_wrong_password() {
        when(users.findByEmail("garen@demaciabook.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("garen@demaciabook.com", "whatever1"), null, null))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("invalid_credentials"));
    }

    @Test
    void login_wrong_password_fails() {
        User user = TestUsers.user();
        when(users.findByEmail("garen@demaciabook.com")).thenReturn(Optional.of(user));
        String hash = new BCryptPasswordEncoder(10).encode("demaciaforever222");
        when(credentials.findById(TestUsers.USER_ID))
            .thenReturn(Optional.of(new Credential(TestUsers.USER_ID, hash)));

        assertThatThrownBy(() -> service.login(new LoginRequest("garen@demaciabook.com", "wrongpass1"), null, null))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("invalid_credentials"));
    }

    @Test
    void login_deleted_user_fails() {
        User deleted = TestUsers.user();
        deleted.setDeletedAt(Instant.now());
        when(users.findByEmail("garen@demaciabook.com")).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.login(new LoginRequest("garen@demaciabook.com", "demaciaforever222"), null, null))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("invalid_credentials"));
    }

    @Test
    void login_locks_out_after_too_many_attempts_with_the_bad_credentials_envelope() {
        User user = TestUsers.user();
        when(users.findByEmail("garen@demaciabook.com")).thenReturn(Optional.of(user));
        String hash = new BCryptPasswordEncoder(10).encode("demaciaforever222");
        when(credentials.findById(TestUsers.USER_ID))
            .thenReturn(Optional.of(new Credential(TestUsers.USER_ID, hash)));

        // burn the whole 10-attempt window on wrong passwords
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> service.login(new LoginRequest("garen@demaciabook.com", "wrongpass1"), null, null))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                    e -> assertThat(e.code()).isEqualTo("invalid_credentials"));
        }

        // attempt 11 is throttled — even with the CORRECT password — but must
        // be indistinguishable from a wrong password (no account/lock leak)
        assertThatThrownBy(() -> service.login(new LoginRequest("garen@demaciabook.com", "demaciaforever222"), null, null))
            .isInstanceOfSatisfying(UnauthorizedException.class, e -> {
                assertThat(e.code()).isEqualTo("invalid_credentials");
                assertThat(e.getMessage()).isEqualTo("Email or password is incorrect");
            });
    }

    // ── refresh / logout ─────────────────────────────────────

    @Test
    void refresh_hands_out_the_rotated_token_not_a_new_family() {
        RefreshToken successor = new RefreshToken();
        successor.setUserId(TestUsers.USER_ID);
        successor.setFamilyId(FAMILY);
        when(refreshTokens.rotate("old-raw"))
            .thenReturn(new RefreshTokenService.Rotated(successor, "new-raw"));
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID)).thenReturn(Optional.of(TestUsers.user()));
        when(userRoles.findRoleIdsByUserId(TestUsers.USER_ID)).thenReturn(List.of("user"));
        when(jwt.issue(any(), anyList())).thenReturn("access-jwt");

        var result = service.refresh("old-raw", null, null);

        assertThat(result.refreshToken()).isEqualTo("new-raw"); // the successor, same family
        assertThat(result.accessToken()).isEqualTo("access-jwt");
        verify(refreshTokens, never()).issue(any(), any(), any()); // no accidental new family
    }

    @Test
    void refresh_after_account_deletion_fails() {
        RefreshToken successor = new RefreshToken();
        successor.setUserId(TestUsers.USER_ID);
        when(refreshTokens.rotate("old-raw"))
            .thenReturn(new RefreshTokenService.Rotated(successor, "new-raw"));
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("old-raw", null, null))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("account_deleted"));
    }

    @Test
    void logout_delegates_to_revoke() {
        service.logout("raw");
        verify(refreshTokens).revoke("raw");
    }

    // ── capture helpers ──────────────────────────────────────

    private User savedUser() {
        var c = ArgumentCaptor.forClass(User.class);
        verify(users).save(c.capture());
        return c.getValue();
    }

    private Credential savedCredential() {
        var c = ArgumentCaptor.forClass(Credential.class);
        verify(credentials).save(c.capture());
        return c.getValue();
    }

    private UserProfile savedProfile() {
        var c = ArgumentCaptor.forClass(UserProfile.class);
        verify(profiles).save(c.capture());
        return c.getValue();
    }

    private UserRole savedRole() {
        var c = ArgumentCaptor.forClass(UserRole.class);
        verify(userRoles).save(c.capture());
        return c.getValue();
    }
}
