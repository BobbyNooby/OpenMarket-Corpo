package dev.bob.openmarket.auth.admin;

import dev.bob.openmarket.auth.common.BadRequestException;
import dev.bob.openmarket.auth.common.ConflictException;
import dev.bob.openmarket.auth.common.ForbiddenException;
import dev.bob.openmarket.auth.common.NotFoundException;
import dev.bob.openmarket.auth.domain.Ban;
import dev.bob.openmarket.auth.domain.OutboxEvent;
import dev.bob.openmarket.auth.domain.UserRole;
import dev.bob.openmarket.auth.domain.Warning;
import dev.bob.openmarket.auth.repository.BanRepository;
import dev.bob.openmarket.auth.repository.CredentialRepository;
import dev.bob.openmarket.auth.repository.OAuthAccountRepository;
import dev.bob.openmarket.auth.repository.OutboxEventRepository;
import dev.bob.openmarket.auth.repository.UserProfileRepository;
import dev.bob.openmarket.auth.repository.UserRepository;
import dev.bob.openmarket.auth.repository.UserRoleRepository;
import dev.bob.openmarket.auth.repository.WarningRepository;
import dev.bob.openmarket.auth.token.RefreshTokenService;
import dev.bob.openmarket.auth.user.UsernameDeriver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moderation + GDPR surface (Phase E). Bans are enforced at login/refresh
 * here; the gateway's Redis blocklist (fed by the `user.banned` outbox
 * event) closes the ≤15-min access-token gap fleet-wide.
 *
 * <p>Authorization is defence in depth: @PreAuthorize checks the JWT at the
 * edge, then these methods re-check the DB — the actor's live roles, strict
 * rank ordering (nobody moderates an equal) and last-owner protection.
 * Every action lands in the audit log in the same transaction.
 */
@Service
public class AdminService {

    private final UserRepository users;
    private final UserRoleRepository userRoles;
    private final UserProfileRepository profiles;
    private final OAuthAccountRepository oauthAccounts;
    private final CredentialRepository credentials;
    private final BanRepository bans;
    private final WarningRepository warnings;
    private final OutboxEventRepository outbox;
    private final AuditService audit;
    private final RefreshTokenService refreshTokens;
    private final ObjectMapper mapper;

    public AdminService(UserRepository users,
                        UserRoleRepository userRoles,
                        UserProfileRepository profiles,
                        OAuthAccountRepository oauthAccounts,
                        CredentialRepository credentials,
                        BanRepository bans,
                        WarningRepository warnings,
                        OutboxEventRepository outbox,
                        AuditService audit,
                        RefreshTokenService refreshTokens,
                        ObjectMapper mapper) {
        this.users = users;
        this.userRoles = userRoles;
        this.profiles = profiles;
        this.oauthAccounts = oauthAccounts;
        this.credentials = credentials;
        this.bans = bans;
        this.warnings = warnings;
        this.outbox = outbox;
        this.audit = audit;
        this.refreshTokens = refreshTokens;
        this.mapper = mapper;
    }

    // ── queries ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResult list(String query, int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
            Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = (query == null || query.isBlank())
            ? users.findAllByDeletedAtIsNull(pageable)
            : users.search(query.trim().toLowerCase(), pageable);
        List<Item> items = result.getContent().stream().map(u -> new Item(
            u.getId(), u.getEmail(), u.getName(), u.isEmailVerified(), u.getDeletedAt() != null,
            userRoles.findRoleIdsByUserId(u.getId()), banned(u.getId()))).toList();
        return new PageResult(items, result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(UUID userId) {
        var user = users.findById(userId)
            .orElseThrow(() -> new NotFoundException("user_not_found", "User not found"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", user.getId());
        out.put("email", user.getEmail());
        out.put("name", user.getName());
        out.put("emailVerified", user.isEmailVerified());
        out.put("deletedAt", user.getDeletedAt());
        out.put("createdAt", user.getCreatedAt());
        out.put("roles", userRoles.findRoleIdsByUserId(userId));
        out.put("bans", bans.findByUserIdOrderByBannedAtDesc(userId));
        out.put("warnings", warnings.findByUserIdOrderByCreatedAtDesc(userId));
        return out;
    }

    // ── moderation ───────────────────────────────────────────

    @Transactional
    public Ban ban(UUID actorId, UUID userId, String reason, Instant expiresAt, String ip) {
        var user = users.findByIdAndDeletedAtIsNull(userId)
            .orElseThrow(() -> new NotFoundException("user_not_found", "User not found"));
        if (bans.findFirstByUserIdAndLiftedAtIsNullOrderByBannedAtDesc(userId)
            .filter(b -> b.isActive(Instant.now())).isPresent()) {
            throw new ConflictException("already_banned", "This user is already banned", null);
        }
        Ban ban = new Ban();
        ban.setUserId(user.getId());
        ban.setBannedBy(actorId);
        ban.setReason(reason);
        ban.setExpiresAt(expiresAt);
        bans.save(ban);

        // a ban kills every live session immediately
        refreshTokens.revokeAllForUser(userId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId.toString());
        payload.put("reason", reason == null ? "" : reason);
        payload.put("bannedBy", actorId.toString());
        if (expiresAt != null) {
            payload.put("expiresAt", expiresAt.toString());
        }
        emit("user", userId, "user.banned", payload);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason == null ? "" : reason);
        if (expiresAt != null) {
            details.put("expiresAt", expiresAt.toString());
        }
        audit.record(actorId, "user.banned", userId, details, ip);
        return ban;
    }

    @Transactional
    public void unban(UUID actorId, UUID userId, String ip) {
        Ban active = bans.findFirstByUserIdAndLiftedAtIsNullOrderByBannedAtDesc(userId)
            .filter(b -> b.isActive(Instant.now()))
            .orElseThrow(() -> new NotFoundException("ban_not_found", "No active ban for this user"));
        active.setLiftedAt(Instant.now());
        emit("user", userId, "user.unbanned", Map.of("userId", userId.toString()));
        audit.record(actorId, "user.unbanned", userId, null, ip);
    }

    @Transactional
    public Warning warn(UUID actorId, UUID userId, String reason, String ip) {
        var user = users.findByIdAndDeletedAtIsNull(userId)
            .orElseThrow(() -> new NotFoundException("user_not_found", "User not found"));
        Warning warning = new Warning();
        warning.setUserId(user.getId());
        warning.setWarnedBy(actorId);
        warning.setReason(reason);
        warnings.save(warning);
        audit.record(actorId, "user.warned", userId,
            Map.of("reason", reason == null ? "" : reason), ip);
        return warning;
    }

    @Transactional
    public List<String> setRoles(UUID actorId, UUID userId, List<String> roleIds, String ip) {
        users.findById(userId)
            .orElseThrow(() -> new NotFoundException("user_not_found", "User not found"));
        for (String role : roleIds) {
            if (!ROLE_IDS.contains(role)) {
                throw new BadRequestException("unknown_role", "Unknown role: " + role, "roles");
            }
        }
        // H1: the actor's live roles come from the DB, never the JWT (a
        // stolen-but-valid token keeps its old claims until it expires).
        List<String> actorRoles = userRoles.findRoleIdsByUserId(actorId);
        if (actorId.equals(userId)) {
            throw new ForbiddenException("self_role_change", "You cannot change your own roles");
        }
        List<String> newRoles = roleIds.stream().distinct().toList();
        if (newRoles.contains("owner") && !actorRoles.contains("owner")) {
            throw new ForbiddenException("insufficient_role", "Only an owner can grant the owner role");
        }
        List<String> oldRoles = userRoles.findRoleIdsByUserId(userId);
        if (oldRoles.contains("owner") && !actorRoles.contains("owner")) {
            throw new ForbiddenException("insufficient_role", "Only an owner can change an owner's roles");
        }
        if (oldRoles.contains("owner") && !newRoles.contains("owner")
            && userRoles.countLiveOwnersExcluding(userId) == 0) {
            throw new ConflictException("last_owner", "Cannot remove the last owner", null);
        }
        List<String> newRoles = roleIds.stream().distinct().toList();
        // replace wholesale; affects future tokens only. The bulk delete must
        // hit the DB BEFORE the new inserts (unique constraint on the pair),
        // so it flushes immediately instead of waiting for commit ordering.
        userRoles.deleteAllForUser(userId);
        for (String role : newRoles) {
            UserRole assignment = new UserRole();
            assignment.setUserId(userId);
            assignment.setRoleId(role);
            userRoles.save(assignment);
        }
        emit("user", userId, "user.roles_changed",
            Map.of("userId", userId.toString(), "newRoles", newRoles));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("oldRoles", oldRoles);
        details.put("newRoles", newRoles);
        audit.record(actorId, "user.roles_changed", userId, details, ip);
        return newRoles;
    }

    // ── GDPR ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> export(UUID userId) {
        var user = users.findById(userId)
            .orElseThrow(() -> new NotFoundException("user_not_found", "User not found"));
        Map<String, Object> userMap = new LinkedHashMap<>();
        userMap.put("id", user.getId());
        userMap.put("email", user.getEmail());
        userMap.put("name", user.getName());
        userMap.put("emailVerified", user.isEmailVerified());
        userMap.put("createdAt", user.getCreatedAt());
        userMap.put("deletedAt", user.getDeletedAt()); // nullable — no Map.of here
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", userMap);
        out.put("roles", userRoles.findRoleIdsByUserId(userId));
        out.put("bans", bans.findByUserIdOrderByBannedAtDesc(userId));
        out.put("warnings", warnings.findByUserIdOrderByCreatedAtDesc(userId));
        return out;
    }

    /** Soft-anonymize now; cross-service erasure happens via the user.deleted saga. */
    @Transactional
    public void erase(UUID actorId, UUID userId, String ip) {
        var user = users.findById(userId)
            .orElseThrow(() -> new NotFoundException("user_not_found", "User not found"));

        user.setEmail("erased-" + user.getId().toString().substring(0, 8) + "@erased.invalid");
        user.setName("Erased User");
        user.setAvatarUrl(null);
        user.setEmailVerified(false);
        user.setDeletedAt(Instant.now());

        refreshTokens.revokeAllForUser(userId);
        emit("user", userId, "user.deleted", Map.of("userId", userId.toString(), "erased", true));
        audit.record(actorId, "user.erased", userId, null, ip);
    }

    // ── plumbing ─────────────────────────────────────────────

    private boolean banned(UUID userId) {
        return bans.findFirstByUserIdAndLiftedAtIsNullOrderByBannedAtDesc(userId)
            .filter(b -> b.isActive(Instant.now())).isPresent();
    }

    private void emit(String aggregateType, UUID aggregateId, String topic, Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setTopic(topic);
        event.setPayload(toJson(payload));
        outbox.save(event);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize outbox payload", e);
        }
    }

    private static final List<String> ROLE_IDS = List.of("user", "moderator", "admin", "owner");

    public record Item(UUID id, String email, String name, boolean emailVerified,
                       boolean deleted, List<String> roles, boolean banned) {
    }

    public record PageResult(List<Item> items, int page, int size, long total) {
    }
}
