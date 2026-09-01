package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.common.ConflictException;
import dev.bob.openmarket.auth.common.NotFoundException;
import dev.bob.openmarket.auth.common.UnauthorizedException;
import dev.bob.openmarket.auth.domain.Credential;
import dev.bob.openmarket.auth.domain.OAuthAccount;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Password-credential lifecycle guards: add-only-once, change requires the
 * current password + revokes other devices, remove requires another login
 * method. These guards are what prevent lockouts and hostile takeovers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceCredentialTest {

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
        service = new AuthService(users, profiles, credentials, oauthAccounts, userRoles, bans,            refreshTokens, jwt, new BCryptPasswordEncoder(10));
        when(credentials.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Credential credentialWith(String password) {
        return new Credential(TestUsers.USER_ID, new BCryptPasswordEncoder(10).encode(password));
    }

    // ── add ──────────────────────────────────────────────────

    @Test
    void addPassword_stores_a_hash_for_oauth_only_users() {
        when(credentials.existsById(TestUsers.USER_ID)).thenReturn(false);

        service.addPassword(TestUsers.USER_ID, "demaciaforever222");

        var c = ArgumentCaptor.forClass(Credential.class);
        verify(credentials).save(c.capture());
        assertThat(c.getValue().getPasswordHash()).startsWith("$2").isNotEqualTo("demaciaforever222");
    }

    @Test
    void addPassword_on_account_that_already_has_one_conflicts() {
        when(credentials.existsById(TestUsers.USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.addPassword(TestUsers.USER_ID, "whatever123"))
            .isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.code()).isEqualTo("password_exists"));
    }

    // ── change ───────────────────────────────────────────────

    @Test
    void changePassword_without_existing_password_is_404() {
        when(credentials.findById(TestUsers.USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changePassword(TestUsers.USER_ID, "a", "b", FAMILY))
            .isInstanceOfSatisfying(NotFoundException.class,
                e -> assertThat(e.code()).isEqualTo("password_not_set"));
    }

    @Test
    void changePassword_with_wrong_current_password_is_401() {
        when(credentials.findById(TestUsers.USER_ID)).thenReturn(Optional.of(credentialWith("demaciaforever222")));

        assertThatThrownBy(() -> service.changePassword(TestUsers.USER_ID, "wrongpass99", "b", FAMILY))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("invalid_credentials"));
    }

    @Test
    void changePassword_updates_hash_and_revokes_other_devices_only() {
        Credential cred = credentialWith("demaciaforever222");
        when(credentials.findById(TestUsers.USER_ID)).thenReturn(Optional.of(cred));

        service.changePassword(TestUsers.USER_ID, "demaciaforever222", "theMightOfDemacia99", FAMILY);

        assertThat(cred.getPasswordHash()).startsWith("$2").isNotEqualTo(credentialWith("x").getPasswordHash());
        verify(refreshTokens).revokeAllForUserExcept(TestUsers.USER_ID, FAMILY);
    }

    // ── remove ───────────────────────────────────────────────

    @Test
    void removePassword_requires_a_password_to_exist() {
        when(credentials.findById(TestUsers.USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removePassword(TestUsers.USER_ID, "x"))
            .isInstanceOfSatisfying(NotFoundException.class,
                e -> assertThat(e.code()).isEqualTo("password_not_set"));
    }

    @Test
    void removePassword_verifies_current_password() {
        when(credentials.findById(TestUsers.USER_ID)).thenReturn(Optional.of(credentialWith("demaciaforever222")));

        assertThatThrownBy(() -> service.removePassword(TestUsers.USER_ID, "wrongpass99"))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("invalid_credentials"));
    }

    @Test
    void removePassword_is_blocked_if_it_is_the_only_login_method() {
        when(credentials.findById(TestUsers.USER_ID)).thenReturn(Optional.of(credentialWith("demaciaforever222")));
        when(oauthAccounts.findByUserId(TestUsers.USER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.removePassword(TestUsers.USER_ID, "demaciaforever222"))
            .isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.code()).isEqualTo("last_login_method"));
        verify(credentials, never()).delete(any());
    }

    @Test
    void removePassword_with_oauth_remaining_succeeds() {
        Credential cred = credentialWith("demaciaforever222");
        when(credentials.findById(TestUsers.USER_ID)).thenReturn(Optional.of(cred));
        OAuthAccount discord = new OAuthAccount();
        discord.setUserId(TestUsers.USER_ID);
        when(oauthAccounts.findByUserId(TestUsers.USER_ID)).thenReturn(List.of(discord));

        service.removePassword(TestUsers.USER_ID, "demaciaforever222");

        verify(credentials).delete(cred);
        verify(refreshTokens, never()).revokeAllForUserExcept(eq(TestUsers.USER_ID), any());
    }
}
