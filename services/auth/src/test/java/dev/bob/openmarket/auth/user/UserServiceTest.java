package dev.bob.openmarket.auth.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bob.openmarket.auth.common.ConflictException;
import dev.bob.openmarket.auth.common.NotFoundException;
import dev.bob.openmarket.auth.domain.OAuthAccount;
import dev.bob.openmarket.auth.domain.OutboxEvent;
import dev.bob.openmarket.auth.domain.User;
import dev.bob.openmarket.auth.domain.UserProfile;
import dev.bob.openmarket.auth.repository.CredentialRepository;
import dev.bob.openmarket.auth.repository.OAuthAccountRepository;
import dev.bob.openmarket.auth.repository.OutboxEventRepository;
import dev.bob.openmarket.auth.repository.UserProfileRepository;
import dev.bob.openmarket.auth.repository.UserRepository;
import dev.bob.openmarket.auth.repository.UserRoleRepository;
import dev.bob.openmarket.auth.support.TestUsers;
import dev.bob.openmarket.auth.token.RefreshTokenService;
import dev.bob.openmarket.auth.user.dto.MeResponse;
import dev.bob.openmarket.auth.user.dto.UpdateMeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Profile mapping (JSON-string columns → typed maps), loginMethods
 * aggregation, partial updates, and the soft-delete + session-revocation
 * combination.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    @Mock UserRepository users;
    @Mock UserProfileRepository profiles;
    @Mock CredentialRepository credentials;
    @Mock OAuthAccountRepository oauthAccounts;
    @Mock UserRoleRepository userRoles;
    @Mock OutboxEventRepository outbox;
    @Mock RefreshTokenService refreshTokens;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(users, profiles, credentials, oauthAccounts, userRoles, outbox,
            refreshTokens, new ObjectMapper());
    }

    private User user() {
        return TestUsers.user();
    }

    private UserProfile profile() {
        UserProfile p = new UserProfile();
        p.setUserId(TestUsers.USER_ID);
        p.setUsername("garen");
        p.setSocialLinks("{\"discord\":\"garen\"}");
        p.setNotificationPreferences("{}");
        p.setLanguage("en");
        return p;
    }

    @Test
    void me_maps_profile_json_and_aggregates_login_methods_sorted() {
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID)).thenReturn(Optional.of(user()));
        when(profiles.findById(TestUsers.USER_ID)).thenReturn(Optional.of(profile()));
        when(userRoles.findRoleIdsByUserId(TestUsers.USER_ID)).thenReturn(List.of("user"));
        when(credentials.existsById(TestUsers.USER_ID)).thenReturn(true);
        OAuthAccount discord = new OAuthAccount();
        discord.setProvider("discord");
        OAuthAccount github = new OAuthAccount();
        github.setProvider("github");
        when(oauthAccounts.findByUserId(TestUsers.USER_ID)).thenReturn(List.of(github, discord));

        MeResponse me = service.me(TestUsers.USER_ID);

        assertThat(me.loginMethods().password()).isTrue();
        assertThat(me.loginMethods().providers()).containsExactly("discord", "github"); // sorted
        assertThat(me.profile().socialLinks()).containsEntry("discord", "garen");
        assertThat(me.roles()).containsExactly("user");
    }

    @Test
    void me_without_password_reports_password_false() {
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID)).thenReturn(Optional.of(user()));
        when(profiles.findById(TestUsers.USER_ID)).thenReturn(Optional.of(profile()));
        when(userRoles.findRoleIdsByUserId(TestUsers.USER_ID)).thenReturn(List.of("user"));
        when(credentials.existsById(TestUsers.USER_ID)).thenReturn(false);
        when(oauthAccounts.findByUserId(TestUsers.USER_ID)).thenReturn(List.of());

        assertThat(service.me(TestUsers.USER_ID).loginMethods().password()).isFalse();
        assertThat(service.me(TestUsers.USER_ID).loginMethods().providers()).isEmpty();
    }

    @Test
    void me_malformed_stored_json_degrades_to_empty_maps() {
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID)).thenReturn(Optional.of(user()));
        UserProfile broken = profile();
        broken.setSocialLinks("not-json{");
        when(profiles.findById(TestUsers.USER_ID)).thenReturn(Optional.of(broken));
        when(userRoles.findRoleIdsByUserId(TestUsers.USER_ID)).thenReturn(List.of());
        when(credentials.existsById(TestUsers.USER_ID)).thenReturn(false);
        when(oauthAccounts.findByUserId(TestUsers.USER_ID)).thenReturn(List.of());

        assertThat(service.me(TestUsers.USER_ID).profile().socialLinks()).isEmpty();
    }

    @Test
    void me_unknown_user_throws_user_not_found() {
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.me(TestUsers.USER_ID))
            .isInstanceOfSatisfying(NotFoundException.class,
                e -> assertThat(e.code()).isEqualTo("user_not_found"));
    }

    @Test
    void update_applies_only_present_fields() {
        User u = user();
        UserProfile p = profile();
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID)).thenReturn(Optional.of(u));
        when(profiles.findById(TestUsers.USER_ID)).thenReturn(Optional.of(p));
        when(userRoles.findRoleIdsByUserId(TestUsers.USER_ID)).thenReturn(List.of());
        when(credentials.existsById(TestUsers.USER_ID)).thenReturn(true);
        when(oauthAccounts.findByUserId(TestUsers.USER_ID)).thenReturn(List.of());

        service.update(TestUsers.USER_ID, new UpdateMeRequest(
            "Crownguard", null, "DEMACIA!", java.util.Map.of("discord", "garen"),
            null, null, null, null));

        assertThat(u.getName()).isEqualTo("Crownguard");
        assertThat(p.getBio()).isEqualTo("DEMACIA!");
        assertThat(p.getSocialLinks()).contains("discord");
        assertThat(p.getUsername()).isEqualTo("garen"); // untouched — null in request
        assertThat(p.getAccentColor()).isNull();
    }

    @Test
    void update_username_conflict_throws_field_error() {
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID)).thenReturn(Optional.of(user()));
        when(profiles.findById(TestUsers.USER_ID)).thenReturn(Optional.of(profile()));
        when(profiles.existsByUsername("someoneelse")).thenReturn(true);

        assertThatThrownBy(() -> service.update(TestUsers.USER_ID,
            new UpdateMeRequest(null, "someoneelse", null, null, null, null, null, null)))
            .isInstanceOfSatisfying(ConflictException.class, e -> {
                assertThat(e.code()).isEqualTo("username_taken");
                assertThat(e.field()).isEqualTo("username");
            });
    }

    @Test
    void update_username_unique_race_surfaces_as_username_taken() {
        // check-then-save race: the pre-check passes but the unique index
        // fires at flush — must surface as the same 409, not a 500
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID)).thenReturn(Optional.of(user()));
        when(profiles.findById(TestUsers.USER_ID)).thenReturn(Optional.of(profile()));
        when(profiles.existsByUsername("freshname")).thenReturn(false);
        when(profiles.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_username"));

        assertThatThrownBy(() -> service.update(TestUsers.USER_ID,
            new UpdateMeRequest(null, "freshname", null, null, null, null, null, null)))
            .isInstanceOfSatisfying(ConflictException.class, e -> {
                assertThat(e.code()).isEqualTo("username_taken");
                assertThat(e.field()).isEqualTo("username");
            });
    }

    @Test
    void delete_tombstones_email_revokes_sessions_and_emits_user_deleted() {
        User u = user();
        when(users.findByIdAndDeletedAtIsNull(TestUsers.USER_ID)).thenReturn(Optional.of(u));
        when(outbox.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.delete(TestUsers.USER_ID);

        assertThat(u.getDeletedAt()).isNotNull();
        // tombstone frees the address: existsByEmail("garen@demaciabook.com")
        // no longer matches, so re-registration succeeds
        assertThat(u.getEmail()).isEqualTo("deleted-11111111@deleted.invalid");
        verify(refreshTokens).revokeAllForUser(TestUsers.USER_ID);
        var c = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(c.capture());
        assertThat(c.getValue().getAggregateType()).isEqualTo("user");
        assertThat(c.getValue().getAggregateId()).isEqualTo(TestUsers.USER_ID);
        assertThat(c.getValue().getTopic()).isEqualTo("user.deleted");
        assertThat(c.getValue().getPayload()).contains("\"userId\":\"" + TestUsers.USER_ID + "\"")
            .contains("\"erased\":false");
    }
}
