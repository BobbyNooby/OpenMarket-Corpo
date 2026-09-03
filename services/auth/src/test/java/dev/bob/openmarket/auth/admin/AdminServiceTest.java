package dev.bob.openmarket.auth.admin;

import dev.bob.openmarket.auth.common.ConflictException;
import dev.bob.openmarket.auth.common.ForbiddenException;
import dev.bob.openmarket.auth.domain.Ban;
import dev.bob.openmarket.auth.domain.Credential;
import dev.bob.openmarket.auth.domain.OutboxEvent;
import dev.bob.openmarket.auth.domain.User;
import dev.bob.openmarket.auth.domain.UserProfile;
import dev.bob.openmarket.auth.repository.BanRepository;
import dev.bob.openmarket.auth.repository.CredentialRepository;
import dev.bob.openmarket.auth.repository.OAuthAccountRepository;
import dev.bob.openmarket.auth.repository.OutboxEventRepository;
import dev.bob.openmarket.auth.repository.UserProfileRepository;
import dev.bob.openmarket.auth.repository.UserRepository;
import dev.bob.openmarket.auth.repository.UserRoleRepository;
import dev.bob.openmarket.auth.repository.WarningRepository;
import dev.bob.openmarket.auth.support.TestUsers;
import dev.bob.openmarket.auth.token.RefreshTokenService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
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
 * The service-side authorization guards (H1/H2) and the audit/outbox bookkeeping.
 * @PreAuthorize checks the JWT at the edge; here the actor's *live DB roles*
 * decide, so a stale token or a sneaky equal-rank action still bounces.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminServiceTest {

    private static final UUID ACTOR = TestUsers.USER_ID;
    private static final UUID TARGET = TestUsers.OTHER_ID;
    private static final String IP = "203.0.113.7";

    @Mock UserRepository users;
    @Mock UserRoleRepository userRoles;
    @Mock UserProfileRepository profiles;
    @Mock OAuthAccountRepository oauthAccounts;
    @Mock CredentialRepository credentials;
    @Mock BanRepository bans;
    @Mock WarningRepository warnings;
    @Mock OutboxEventRepository outbox;
    @Mock AuditService audit;
    @Mock RefreshTokenService refreshTokens;

    private AdminService service;

    @BeforeEach
    void setUp() {
        service = new AdminService(users, userRoles, profiles, oauthAccounts, credentials,
            bans, warnings, outbox, audit, refreshTokens, new ObjectMapper());
        when(users.findById(any())).thenReturn(Optional.of(TestUsers.user(TARGET, "noxus@noxus.gg")));
        when(users.findByIdAndDeletedAtIsNull(any()))
            .thenReturn(Optional.of(TestUsers.user(TARGET, "noxus@noxus.gg")));
        when(userRoles.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bans.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(warnings.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outbox.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── H1: setRoles guards ──────────────────────────────────

    @Test
    void setRoles_admin_granting_owner_is_forbidden() {
        when(userRoles.findRoleIdsByUserId(ACTOR)).thenReturn(List.of("admin"));
        when(userRoles.findRoleIdsByUserId(TARGET)).thenReturn(List.of("user"));

        assertThatThrownBy(() -> service.setRoles(ACTOR, TARGET, List.of("owner"), IP))
            .isInstanceOfSatisfying(ForbiddenException.class,
                e -> assertThat(e.code()).isEqualTo("insufficient_role"));
        verify(userRoles, never()).deleteAllForUser(any());
    }

    @Test
    void setRoles_self_change_is_forbidden() {
        when(userRoles.findRoleIdsByUserId(ACTOR)).thenReturn(List.of("admin"));

        assertThatThrownBy(() -> service.setRoles(ACTOR, ACTOR, List.of("user"), IP))
            .isInstanceOfSatisfying(ForbiddenException.class,
                e -> assertThat(e.code()).isEqualTo("self_role_change"));
        verify(userRoles, never()).deleteAllForUser(any());
    }

    @Test
    void setRoles_dropping_the_last_owner_conflicts() {
        when(userRoles.findRoleIdsByUserId(ACTOR)).thenReturn(List.of("owner"));
        when(userRoles.findRoleIdsByUserId(TARGET)).thenReturn(List.of("owner"));
        when(userRoles.countLiveOwnersExcluding(TARGET)).thenReturn(0L);

        assertThatThrownBy(() -> service.setRoles(ACTOR, TARGET, List.of("admin"), IP))
            .isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.code()).isEqualTo("last_owner"));
        verify(userRoles, never()).deleteAllForUser(any());
    }

    @Test
    void setRoles_removing_one_of_two_owners_succeeds_and_audits() {
        when(userRoles.findRoleIdsByUserId(ACTOR)).thenReturn(List.of("owner"));
        when(userRoles.findRoleIdsByUserId(TARGET)).thenReturn(List.of("owner"));
        when(userRoles.countLiveOwnersExcluding(TARGET)).thenReturn(1L);

        var roles = service.setRoles(ACTOR, TARGET, List.of("moderator"), IP);

        assertThat(roles).containsExactly("moderator");
        verify(userRoles).deleteAllForUser(TARGET);
        var event = savedEvent();
        assertThat(event.getTopic()).isEqualTo("user.roles_changed");
        assertThat(event.getPayload()).contains("\"userId\":\"" + TARGET + "\"")
            .contains("\"newRoles\":[\"moderator\"]");

        var details = auditDetailsFor("user.roles_changed");
        assertThat(details.get("oldRoles")).isEqualTo(List.of("owner"));
        assertThat(details.get("newRoles")).isEqualTo(List.of("moderator"));
    }

    @Test
    void setRoles_owner_grants_admin_succeeds() {
        when(userRoles.findRoleIdsByUserId(ACTOR)).thenReturn(List.of("owner"));
        when(userRoles.findRoleIdsByUserId(TARGET)).thenReturn(List.of("user"));

        var roles = service.setRoles(ACTOR, TARGET, List.of("admin"), IP);

        assertThat(roles).containsExactly("admin");
        assertThat(savedEvent().getTopic()).isEqualTo("user.roles_changed");
        verify(audit).record(eq(ACTOR), eq("user.roles_changed"), eq(TARGET), any(), eq(IP));
    }

    // ── H2: rank ordering on ban/warn ────────────────────────

    @Test
    void ban_moderator_banning_admin_is_forbidden() {
        when(userRoles.findRoleIdsByUserId(ACTOR)).thenReturn(List.of("moderator"));
        when(userRoles.findRoleIdsByUserId(TARGET)).thenReturn(List.of("admin"));

        assertThatThrownBy(() -> service.ban(ACTOR, TARGET, "spam", null, IP))
            .isInstanceOfSatisfying(ForbiddenException.class,
                e -> assertThat(e.code()).isEqualTo("target_outranks"));
        verify(bans, never()).save(any());
    }

    @Test
    void ban_owner_banning_owner_is_forbidden_nobody_moderates_an_equal() {
        when(userRoles.findRoleIdsByUserId(ACTOR)).thenReturn(List.of("owner"));
        when(userRoles.findRoleIdsByUserId(TARGET)).thenReturn(List.of("owner"));

        assertThatThrownBy(() -> service.ban(ACTOR, TARGET, "spam", null, IP))
            .isInstanceOfSatisfying(ForbiddenException.class,
                e -> assertThat(e.code()).isEqualTo("target_outranks"));
    }

    @Test
    void ban_admin_banning_moderator_succeeds_and_audits() {
        when(userRoles.findRoleIdsByUserId(ACTOR)).thenReturn(List.of("admin"));
        when(userRoles.findRoleIdsByUserId(TARGET)).thenReturn(List.of("moderator"));
        when(bans.findFirstByUserIdAndLiftedAtIsNullOrderByBannedAtDesc(TARGET))
            .thenReturn(Optional.empty());

        var ban = service.ban(ACTOR, TARGET, "map hacking", null, IP);

        assertThat(ban.getUserId()).isEqualTo(TARGET);
        assertThat(ban.getBannedBy()).isEqualTo(ACTOR);
        verify(refreshTokens).revokeAllForUser(TARGET);
        assertThat(savedEvent().getTopic()).isEqualTo("user.banned");
        var details = auditDetailsFor("user.banned");
        assertThat(details.get("reason")).isEqualTo("map hacking");
        verify(audit).record(eq(ACTOR), eq("user.banned"), eq(TARGET), any(), eq(IP));
    }

    @Test
    void ban_unknown_or_equal_rank_is_checked_before_already_banned() {
        // a moderator cannot even *look* at an active ban on an admin — the
        // rank check fires before the ban lookup
        when(userRoles.findRoleIdsByUserId(ACTOR)).thenReturn(List.of("moderator"));
        when(userRoles.findRoleIdsByUserId(TARGET)).thenReturn(List.of("admin"));

        assertThatThrownBy(() -> service.ban(ACTOR, TARGET, "r", null, IP))
            .isInstanceOf(ForbiddenException.class);
        verify(bans, never()).findFirstByUserIdAndLiftedAtIsNullOrderByBannedAtDesc(any());
    }

    // ── audit rows for the remaining actions ─────────────────

    @Test
    void unban_records_audit_row() {
        Ban active = new Ban();
        active.setUserId(TARGET);
        when(bans.findFirstByUserIdAndLiftedAtIsNullOrderByBannedAtDesc(TARGET))
            .thenReturn(Optional.of(active));

        service.unban(ACTOR, TARGET, IP);

        assertThat(active.getLiftedAt()).isNotNull();
        assertThat(savedEvent().getTopic()).isEqualTo("user.unbanned");
        verify(audit).record(eq(ACTOR), eq("user.unbanned"), eq(TARGET), any(), eq(IP));
    }

    @Test
    void warn_records_audit_row() {
        when(userRoles.findRoleIdsByUserId(ACTOR)).thenReturn(List.of("admin"));
        when(userRoles.findRoleIdsByUserId(TARGET)).thenReturn(List.of("moderator"));

        service.warn(ACTOR, TARGET, "be nice", IP);

        var details = auditDetailsFor("user.warned");
        assertThat(details.get("reason")).isEqualTo("be nice");
        verify(audit).record(eq(ACTOR), eq("user.warned"), eq(TARGET), any(), eq(IP));
    }

    @Test
    void erase_anonymizes_user_profile_and_purges_oauth_and_credentials() {
        User target = TestUsers.user(TARGET, "noxus@noxus.gg");
        when(users.findById(TARGET)).thenReturn(Optional.of(target));
        UserProfile profile = new UserProfile();
        profile.setUserId(TARGET);
        profile.setUsername("noxus");
        profile.setBio("scratch that");
        profile.setSocialLinks("{\"discord\":\"noxus\"}");
        profile.setAccentColor("#ff0000");
        profile.setLanguage("fr");
        profile.setNotificationPreferences("{\"mentions\":true}");
        profile.setAvatarUrl("https://cdn.example.com/noxus.png");
        when(profiles.findById(TARGET)).thenReturn(Optional.of(profile));
        when(profiles.existsByUsername("erased-22222222")).thenReturn(false);
        Credential cred = new Credential(TARGET, "$2a$10$dummyhashdummyhashdummyhashdummyhashdummy");
        when(credentials.findById(TARGET)).thenReturn(Optional.of(cred));

        service.erase(ACTOR, TARGET, IP);

        assertThat(target.getDeletedAt()).isNotNull(); // soft-anonymized in place
        assertThat(target.getEmail()).startsWith("erased-");
        assertThat(profile.getUsername()).isEqualTo("erased-22222222"); // derived from the id
        assertThat(profile.getBio()).isNull();
        assertThat(profile.getSocialLinks()).isNull();
        assertThat(profile.getAvatarUrl()).isNull();
        assertThat(profile.getAccentColor()).isNull();
        assertThat(profile.getLanguage()).isEqualTo("en"); // NOT NULL column → default
        assertThat(profile.getNotificationPreferences()).isEqualTo("{}");
        verify(oauthAccounts).deleteByUserId(TARGET);
        verify(credentials).delete(cred); // bcrypt hash serves no purpose post-erase
        assertThat(savedEvent().getTopic()).isEqualTo("user.deleted"); // unchanged saga event
        verify(audit).record(eq(ACTOR), eq("user.erased"), eq(TARGET), any(), eq(IP));
    }

    @Test
    void erase_without_profile_or_password_skips_purges_gracefully() {
        User target = TestUsers.user(TARGET, "noxus@noxus.gg");
        when(users.findById(TARGET)).thenReturn(Optional.of(target));
        when(profiles.findById(TARGET)).thenReturn(Optional.empty());
        when(credentials.findById(TARGET)).thenReturn(Optional.empty());

        service.erase(ACTOR, TARGET, IP);

        assertThat(target.getDeletedAt()).isNotNull();
        verify(oauthAccounts).deleteByUserId(TARGET);
        verify(credentials, never()).delete(any());
        assertThat(savedEvent().getTopic()).isEqualTo("user.deleted");
        verify(audit).record(eq(ACTOR), eq("user.erased"), eq(TARGET), any(), eq(IP));
    }

    // ── capture helpers ──────────────────────────────────────

    private OutboxEvent savedEvent() {
        var c = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(c.capture());
        return c.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditDetailsFor(String action) {
        var c = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(ACTOR), eq(action), eq(TARGET), c.capture(), eq(IP));
        return (Map<String, Object>) c.getValue();
    }
}
