package dev.bob.openmarket.auth.user;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bob.openmarket.auth.common.ConflictException;
import dev.bob.openmarket.auth.common.NotFoundException;
import dev.bob.openmarket.auth.domain.User;
import dev.bob.openmarket.auth.domain.UserProfile;
import dev.bob.openmarket.auth.repository.CredentialRepository;
import dev.bob.openmarket.auth.repository.OAuthAccountRepository;
import dev.bob.openmarket.auth.repository.UserProfileRepository;
import dev.bob.openmarket.auth.repository.UserRepository;
import dev.bob.openmarket.auth.repository.UserRoleRepository;
import dev.bob.openmarket.auth.token.RefreshTokenService;
import dev.bob.openmarket.auth.user.dto.MeResponse;
import dev.bob.openmarket.auth.user.dto.UpdateMeRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private static final TypeReference<java.util.Map<String, String>> SOCIAL_LINKS =
        new TypeReference<>() {
        };
    private static final TypeReference<java.util.Map<String, Boolean>> NOTIFICATION_PREFS =
        new TypeReference<>() {
        };

    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final CredentialRepository credentials;
    private final OAuthAccountRepository oauthAccounts;
    private final UserRoleRepository userRoles;
    private final RefreshTokenService refreshTokens;
    private final ObjectMapper mapper;

    public UserService(UserRepository users,
                       UserProfileRepository profiles,
                       CredentialRepository credentials,
                       OAuthAccountRepository oauthAccounts,
                       UserRoleRepository userRoles,
                       RefreshTokenService refreshTokens,
                       ObjectMapper mapper) {
        this.users = users;
        this.profiles = profiles;
        this.credentials = credentials;
        this.oauthAccounts = oauthAccounts;
        this.userRoles = userRoles;
        this.refreshTokens = refreshTokens;
        this.mapper = mapper;
    }

    public User getById(UUID id) {
        return users.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new NotFoundException("user_not_found", "User not found"));
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        User user = getById(userId);
        UserProfile profile = profileOf(user.getId());
        return new MeResponse(
            user.getId(),
            user.getEmail(),
            user.getName(),
            user.getAvatarUrl(),
            user.isEmailVerified(),
            userRoles.findRoleIdsByUserId(user.getId()),
            loginMethods(user.getId()),
            new MeResponse.Profile(
                profile.getUsername(),
                profile.getBio(),
                readJson(profile.getSocialLinks(), SOCIAL_LINKS),
                profile.getAccentColor(),
                profile.getLanguage(),
                readJson(profile.getNotificationPreferences(), NOTIFICATION_PREFS),
                profile.getAvatarUrl()));
    }

    private MeResponse.LoginMethods loginMethods(UUID userId) {
        boolean hasPassword = credentials.existsById(userId);
        List<String> providers = oauthAccounts.findByUserId(userId).stream()
            .map(a -> a.getProvider())
            .sorted()
            .toList();
        return new MeResponse.LoginMethods(hasPassword, providers);
    }

    /** Partial update: only non-null fields of {@code req} are applied. */
    @Transactional
    public MeResponse update(UUID userId, UpdateMeRequest req) {
        User user = getById(userId);
        UserProfile profile = profileOf(user.getId());

        if (req.name() != null) {
            user.setName(req.name().trim());
        }
        if (req.username() != null && !req.username().equals(profile.getUsername())) {
            if (profiles.existsByUsername(req.username())) {
                throw new ConflictException("username_taken", "This username is already taken", "username");
            }
            profile.setUsername(req.username());
        }
        if (req.bio() != null) {
            profile.setBio(req.bio());
        }
        if (req.socialLinks() != null) {
            profile.setSocialLinks(writeJson(req.socialLinks()));
        }
        if (req.accentColor() != null) {
            profile.setAccentColor(req.accentColor());
        }
        if (req.language() != null) {
            profile.setLanguage(req.language());
        }
        if (req.avatarUrl() != null) {
            profile.setAvatarUrl(req.avatarUrl());
        }
        if (req.notificationPreferences() != null) {
            profile.setNotificationPreferences(writeJson(req.notificationPreferences()));
        }

        return me(userId);
    }

    /**
     * Soft delete: the row stays (other services may still reference the id;
     * hard erasure happens via the GDPR saga later) but the account can no
     * longer log in and all its refresh tokens die immediately.
     */
    @Transactional
    public void delete(UUID userId) {
        User user = getById(userId);
        user.setDeletedAt(Instant.now());
        refreshTokens.revokeAllForUser(userId);
    }

    private UserProfile profileOf(UUID userId) {
        return profiles.findById(userId)
            .orElseThrow(() -> new NotFoundException("profile_not_found", "Profile not found"));
    }

    private <K, V> java.util.Map<K, V> readJson(String json, TypeReference<java.util.Map<K, V>> type) {
        if (json == null || json.isBlank()) {
            return java.util.Map.of();
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize profile JSON", e);
        }
    }
}
