package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.common.ConflictException;
import dev.bob.openmarket.auth.common.NotFoundException;
import dev.bob.openmarket.auth.common.UnauthorizedException;
import dev.bob.openmarket.auth.domain.Credential;
import dev.bob.openmarket.auth.domain.OAuthAccount;
import dev.bob.openmarket.auth.domain.User;
import dev.bob.openmarket.auth.domain.UserProfile;
import dev.bob.openmarket.auth.domain.UserRole;
import dev.bob.openmarket.auth.oauth.DiscordUser;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Discord linking decision matrix — the heart of email↔Discord
 * connection. Every row of the table in docs/accounts.md lands here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceDiscordTest {

    private static final UUID FAMILY = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String DISCORD_ID = "223749168869212160";

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
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(profiles.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(oauthAccounts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRoles.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(credentials.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRoles.findRoleIdsByUserId(any())).thenReturn(List.of("user"));
        when(jwt.issue(any(), anyList())).thenReturn("access-jwt");
        when(refreshTokens.issue(any(), any(), any())).thenReturn("refresh-raw");
    }

    private DiscordUser discordUser(String email, boolean verified) {
        return new DiscordUser(DISCORD_ID, "garen", "Garen Crownguard", email, verified);
    }

    private OAuthAccount existingDiscordAccount(UUID userId) {
        OAuthAccount a = new OAuthAccount();
        a.setUserId(userId);
        a.setProvider("discord");
        a.setProviderAccountId(DISCORD_ID);
        return a;
    }

    // ── loginOrSignup ────────────────────────────────────────

    @Test
    void known_discord_account_logs_that_user_in() {
        when(oauthAccounts.findByProviderAndProviderAccountId("discord", DISCORD_ID))
            .thenReturn(Optional.of(existingDiscordAccount(TestUsers.USER_ID)));
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID))
            .thenReturn(Optional.of(TestUsers.user()));

        var result = service.discordLoginOrSignup(
            discordUser("garen@demaciabook.com", true), "at", "UA", "1.2.3.4");

        assertThat(result.accessToken()).isEqualTo("access-jwt");
        verify(users, never()).save(any()); // no identity created
        verify(oauthAccounts, never()).save(any()); // link already exists
    }

    @Test
    void verified_email_matching_existing_user_autolinks_instead_of_duplicating() {
        when(oauthAccounts.findByProviderAndProviderAccountId("discord", DISCORD_ID))
            .thenReturn(Optional.empty());
        User user = TestUsers.user();
        when(users.findByEmail("garen@demaciabook.com")).thenReturn(Optional.of(user));

        var result = service.discordLoginOrSignup(
            discordUser("Garen@DemaciaBook.com", true), "at", null, null);

        assertThat(result.user()).isSameAs(user);
        var saved = capturedAccount();
        assertThat(saved.getUserId()).isEqualTo(TestUsers.USER_ID); // linked to EXISTING user
        verify(users, never()).save(any());
    }

    @Test
    void unknown_verified_email_creates_identity_with_verified_email_and_profile() {
        when(oauthAccounts.findByProviderAndProviderAccountId("discord", DISCORD_ID))
            .thenReturn(Optional.empty());
        when(users.findByEmail("garen@demaciabook.com")).thenReturn(Optional.empty());
        when(profiles.existsByUsername(any())).thenReturn(false);

        var result = service.discordLoginOrSignup(
            discordUser("garen@demaciabook.com", true), "at", null, null);

        User saved = savedUser();
        assertThat(saved.getEmail()).isEqualTo("garen@demaciabook.com");
        assertThat(saved.isEmailVerified()).isTrue(); // Discord verified it
        assertThat(saved.getName()).isEqualTo("Garen Crownguard");

        UserProfile profile = savedProfile();
        assertThat(profile.getUsername()).matches("^[a-z0-9_-]{3,32}$");

        roleCaptor("user"); // default role assigned on Discord signup
        assertThat(result.accessToken()).isEqualTo("access-jwt");
    }

    @Test
    void unverified_discord_email_is_refused() {
        when(oauthAccounts.findByProviderAndProviderAccountId("discord", DISCORD_ID))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.discordLoginOrSignup(
            discordUser("garen@demaciabook.com", false), "at", null, null))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("oauth_email_required"));
        verify(users, never()).save(any());
    }

    @Test
    void missing_discord_email_is_refused() {
        when(oauthAccounts.findByProviderAndProviderAccountId("discord", DISCORD_ID))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.discordLoginOrSignup(
            new DiscordUser(DISCORD_ID, "garen", "Garen", null, true), "at", null, null))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("oauth_email_required"));
    }

    @Test
    void discord_account_of_deleted_user_is_refused() {
        when(oauthAccounts.findByProviderAndProviderAccountId("discord", DISCORD_ID))
            .thenReturn(Optional.of(existingDiscordAccount(TestUsers.USER_ID)));
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.discordLoginOrSignup(
            discordUser("garen@demaciabook.com", true), "at", null, null))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                e -> assertThat(e.code()).isEqualTo("account_deleted"));
    }

    // ── linkDiscord ──────────────────────────────────────────

    @Test
    void link_to_free_discord_account_succeeds() {
        when(oauthAccounts.findByProviderAndProviderAccountId("discord", DISCORD_ID))
            .thenReturn(Optional.empty());

        service.linkDiscord(TestUsers.USER_ID, discordUser(null, false), "at");

        assertThat(capturedAccount().getUserId()).isEqualTo(TestUsers.USER_ID);
    }

    @Test
    void link_to_already_linked_self_is_a_noop_success() {
        when(oauthAccounts.findByProviderAndProviderAccountId("discord", DISCORD_ID))
            .thenReturn(Optional.of(existingDiscordAccount(TestUsers.USER_ID)));

        service.linkDiscord(TestUsers.USER_ID, discordUser(null, false), "at");

        verify(oauthAccounts, never()).save(any());
    }

    @Test
    void link_to_foreign_discord_account_conflicts() {
        when(oauthAccounts.findByProviderAndProviderAccountId("discord", DISCORD_ID))
            .thenReturn(Optional.of(existingDiscordAccount(TestUsers.OTHER_ID)));

        assertThatThrownBy(() -> service.linkDiscord(TestUsers.USER_ID, discordUser(null, false), "at"))
            .isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.code()).isEqualTo("provider_already_linked"));
    }

    // ── unlinkDiscord ────────────────────────────────────────

    @Test
    void unlink_without_discord_link_is_404() {
        when(oauthAccounts.findByUserId(TestUsers.USER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.unlinkDiscord(TestUsers.USER_ID))
            .isInstanceOfSatisfying(NotFoundException.class,
                e -> assertThat(e.code()).isEqualTo("provider_not_linked"));
    }

    @Test
    void unlink_last_login_method_is_blocked() {
        when(oauthAccounts.findByUserId(TestUsers.USER_ID))
            .thenReturn(List.of(existingDiscordAccount(TestUsers.USER_ID)));
        when(credentials.existsById(TestUsers.USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.unlinkDiscord(TestUsers.USER_ID))
            .isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.code()).isEqualTo("last_login_method"));
    }

    @Test
    void unlink_with_password_remaining_succeeds() {
        OAuthAccount discord = existingDiscordAccount(TestUsers.USER_ID);
        when(oauthAccounts.findByUserId(TestUsers.USER_ID)).thenReturn(List.of(discord));
        when(credentials.existsById(TestUsers.USER_ID)).thenReturn(true);

        service.unlinkDiscord(TestUsers.USER_ID);

        verify(oauthAccounts).delete(discord);
    }

    // ── capture helpers ──────────────────────────────────────

    private OAuthAccount capturedAccount() {
        var c = ArgumentCaptor.forClass(OAuthAccount.class);
        verify(oauthAccounts).save(c.capture());
        return c.getValue();
    }

    private UserRole roleCaptor(String roleId) {
        var c = ArgumentCaptor.forClass(UserRole.class);
        verify(userRoles).save(c.capture());
        assertThat(c.getValue().getRoleId()).isEqualTo(roleId);
        return c.getValue();
    }

    private User savedUser() {
        var c = ArgumentCaptor.forClass(User.class);
        verify(users).save(c.capture());
        return c.getValue();
    }

    private UserProfile savedProfile() {
        var c = ArgumentCaptor.forClass(UserProfile.class);
        verify(profiles).save(c.capture());
        return c.getValue();
    }
}
