package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.auth.dto.LoginRequest;
import dev.bob.openmarket.auth.auth.dto.RegisterRequest;
import dev.bob.openmarket.auth.common.ConflictException;
import dev.bob.openmarket.auth.common.ForbiddenException;
import dev.bob.openmarket.auth.common.NotFoundException;
import dev.bob.openmarket.auth.common.UnauthorizedException;
import dev.bob.openmarket.auth.domain.Ban;
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
import dev.bob.openmarket.auth.token.AuthResult;
import dev.bob.openmarket.auth.token.JwtService;
import dev.bob.openmarket.auth.token.RefreshTokenService;
import dev.bob.openmarket.auth.token.TokenPair;
import dev.bob.openmarket.auth.user.UsernameDeriver;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AuthService {

    /** Verify unknown emails against this to keep login timing uniform. */
    private static final String DUMMY_HASH = "$2a$12$Xu9fbIeitcFyMfeQDLl9Bu0FXDfgnidmgRPIeN9xEyq5TrLLx2KSi";
    private static final String DEFAULT_ROLE = "user";
    public static final String PROVIDER_DISCORD = "discord";

    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final CredentialRepository credentials;
    private final OAuthAccountRepository oauthAccounts;
    private final UserRoleRepository userRoles;
    private final BanRepository bans;
    private final RefreshTokenService refreshTokens;
    private final JwtService jwt;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository users,
                       UserProfileRepository profiles,
                       CredentialRepository credentials,
                       OAuthAccountRepository oauthAccounts,
                       UserRoleRepository userRoles,
                       BanRepository bans,
                       RefreshTokenService refreshTokens,
                       JwtService jwt,
                       PasswordEncoder passwordEncoder) {
        this.users = users;
        this.profiles = profiles;
        this.credentials = credentials;
        this.oauthAccounts = oauthAccounts;
        this.userRoles = userRoles;
        this.bans = bans;
        this.refreshTokens = refreshTokens;
        this.jwt = jwt;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AuthResult register(RegisterRequest req, String userAgent, String ip) {
        String email = req.email().trim().toLowerCase();

        if (users.existsByEmail(email)) {
            throw new ConflictException("email_taken", "An account with this email already exists", "email");
        }

        String username = req.username() != null ? req.username() : UsernameDeriver.derive(req.name(), email);
        if (profiles.existsByUsername(username)) {
            throw new ConflictException("username_taken", "This username is already taken", "username");
        }

        User user = new User();
        user.setEmail(email);
        user.setName(req.name().trim());
        users.save(user);

        credentials.save(new Credential(user.getId(), passwordEncoder.encode(req.password())));

        UserProfile profile = new UserProfile();
        profile.setUserId(user.getId());
        profile.setUsername(username);
        profiles.save(profile);

        // bootstrap: the very first account owns the platform
        String role = users.count() == 1 ? "owner" : DEFAULT_ROLE;
        UserRole defaultRole = new UserRole();
        defaultRole.setUserId(user.getId());
        defaultRole.setRoleId(role);
        userRoles.save(defaultRole);

        return AuthResult.of(user, issuePair(user, userAgent, ip));
    }

    @Transactional
    public AuthResult login(LoginRequest req, String userAgent, String ip) {
        String email = req.email().trim().toLowerCase();

        User user = users.findByEmail(email).filter(u -> u.getDeletedAt() == null).orElse(null);
        Credential credential = user != null ? credentials.findById(user.getId()).orElse(null) : null;

        // Always run a bcrypt comparison, even for unknown accounts, so
        // response timing doesn't reveal which emails exist.
        String hash = credential != null ? credential.getPasswordHash() : DUMMY_HASH;
        boolean matches = passwordEncoder.matches(req.password(), hash);
        if (user == null || credential == null || !matches) {
            throw new UnauthorizedException("invalid_credentials", "Email or password is incorrect");
        }

        assertNotBanned(user.getId());
        return AuthResult.of(user, issuePair(user, userAgent, ip));
    }

    /** Consume the presented refresh token, issue the next pair in the family. */
    @Transactional
    public AuthResult refresh(String rawRefreshToken, String userAgent, String ip) {
        var rotated = refreshTokens.rotate(rawRefreshToken);
        User user = users.findByIdAndDeletedAtIsNull(rotated.entity().getUserId())
            .orElseThrow(() -> new UnauthorizedException("account_deleted", "Account no longer exists"));
        assertNotBanned(user.getId());
        String access = jwt.issue(user.getId(), userRoles.findRoleIdsByUserId(user.getId()));
        return new AuthResult(user, access, rotated.rawToken());
    }

    /** Active ban = not lifted and (no expiry or expiry in the future). */
    private void assertNotBanned(UUID userId) {
        bans.findFirstByUserIdAndLiftedAtIsNullOrderByBannedAtDesc(userId)
            .filter(b -> b.isActive(Instant.now()))
            .ifPresent(b -> {
                throw new ForbiddenException("account_banned",
                    "This account is banned" + (b.getExpiresAt() != null
                        ? " until " + b.getExpiresAt() : "") + "; reason: " + b.getReason());
            });
    }

    /**
     * Best-effort logout: unknown/missing refresh tokens are tolerated so the
     * client always ends up with cleared cookies (204 either way). A valid
     * token is revoked; an expired one dies anyway.
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        try {
            refreshTokens.revoke(rawRefreshToken);
        } catch (UnauthorizedException ignored) {
            // nothing to revoke — still return 204
        }
    }

    public List<String> rolesOf(User user) {
        return userRoles.findRoleIdsByUserId(user.getId());
    }

    private TokenPair issuePair(User user, String userAgent, String ip) {
        List<String> roles = userRoles.findRoleIdsByUserId(user.getId());
        String access = jwt.issue(user.getId(), roles);
        String refresh = refreshTokens.issue(user.getId(), userAgent, ip);
        return new TokenPair(access, refresh);
    }

    // ── Discord OAuth (authorization-code grant, see DiscordOAuthController) ──

    /**
     * Callback resolution order: ① known Discord account → login;
     * ② verified Discord email matching an existing account → auto-link;
     * ③ otherwise create a fresh identity. Unverified/no email is refused —
     * `users.email` is our identity anchor and must be trustworthy.
     */
    @Transactional
    public AuthResult discordLoginOrSignup(DiscordUser discordUser, String accessToken, String userAgent, String ip) {
        var existing = oauthAccounts.findByProviderAndProviderAccountId(
            PROVIDER_DISCORD, discordUser.id());

        if (existing.isPresent()) {
            OAuthAccount account = existing.get();
            User user = users.findByIdAndDeletedAtIsNull(account.getUserId())
                .orElseThrow(() -> new UnauthorizedException("account_deleted", "Account no longer exists"));
            account.setAccessToken(accessToken); // keep the provider token fresh
            assertNotBanned(user.getId()); // a ban must also close the OAuth side door
            return AuthResult.of(user, issuePair(user, userAgent, ip));
        }

        if (!discordUser.hasVerifiedEmail()) {
            throw new UnauthorizedException("oauth_email_required",
                "Your Discord account has no verified email; verify it in Discord first");
        }
        String email = discordUser.email().trim().toLowerCase();

        User user = users.findByEmail(email).filter(u -> u.getDeletedAt() == null).orElse(null);
        if (user == null) {
            user = createDiscordUser(email, discordUser);
        } else {
            // auto-link: the verified email proves it's the same person
        }
        saveDiscordAccount(user.getId(), discordUser, accessToken);
        assertNotBanned(user.getId()); // signup (fresh user) can't be banned, but auto-link can
        return AuthResult.of(user, issuePair(user, userAgent, ip));
    }

    /** Connect-Discord-while-logged-in. Links, or no-ops if already this user's. */
    @Transactional
    public void linkDiscord(UUID userId, DiscordUser discordUser, String accessToken) {
        var existing = oauthAccounts.findByProviderAndProviderAccountId(
            PROVIDER_DISCORD, discordUser.id());
        if (existing.isPresent()) {
            if (existing.get().getUserId().equals(userId)) {
                return; // already linked to self — success
            }
            throw new ConflictException("provider_already_linked",
                "This Discord account is already linked to another user", null);
        }
        saveDiscordAccount(userId, discordUser, accessToken);
    }

    @Transactional
    public void unlinkDiscord(UUID userId) {
        List<OAuthAccount> accounts = oauthAccounts.findByUserId(userId);
        OAuthAccount discord = accounts.stream()
            .filter(a -> PROVIDER_DISCORD.equals(a.getProvider()))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("provider_not_linked", "No Discord account is linked"));
        boolean hasPassword = credentials.existsById(userId);
        if (accounts.size() == 1 && !hasPassword) {
            throw new ConflictException("last_login_method",
                "You need at least one login method", null);
        }
        oauthAccounts.delete(discord);
    }

    private User createDiscordUser(String email, DiscordUser discordUser) {
        User user = new User();
        user.setEmail(email);
        user.setName(discordUser.displayName());
        user.setEmailVerified(true); // Discord says the email is verified
        users.save(user);

        String username = UsernameDeriver.derive(discordUser.displayName(), email);
        while (profiles.existsByUsername(username)) {
            username = UsernameDeriver.derive(discordUser.displayName(), email);
        }
        UserProfile profile = new UserProfile();
        profile.setUserId(user.getId());
        profile.setUsername(username);
        profiles.save(profile);

        UserRole defaultRole = new UserRole();
        defaultRole.setUserId(user.getId());
        defaultRole.setRoleId(DEFAULT_ROLE);
        userRoles.save(defaultRole);
        return user;
    }

    private void saveDiscordAccount(UUID userId, DiscordUser discordUser, String accessToken) {
        OAuthAccount account = new OAuthAccount();
        account.setUserId(userId);
        account.setProvider(PROVIDER_DISCORD);
        account.setProviderAccountId(discordUser.id());
        account.setAccessToken(accessToken);
        oauthAccounts.save(account);
    }

    // ── password credentials (add / change / remove) ─────────

    /** For Discord-first users adding an email-style login. */
    @Transactional
    public void addPassword(UUID userId, String password) {
        if (credentials.existsById(userId)) {
            throw new ConflictException("password_exists", "This account already has a password", null);
        }
        credentials.save(new Credential(userId, passwordEncoder.encode(password)));
    }

    /** Changes the password and revokes every other device's session. */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword, UUID keepFamilyId) {
        Credential credential = credentials.findById(userId)
            .orElseThrow(() -> new NotFoundException("password_not_set", "This account has no password"));
        if (!passwordEncoder.matches(currentPassword, credential.getPasswordHash())) {
            throw new UnauthorizedException("invalid_credentials", "Current password is incorrect");
        }
        credential.setPasswordHash(passwordEncoder.encode(newPassword));
        credentials.save(credential);
        refreshTokens.revokeAllForUserExcept(userId, keepFamilyId);
    }

    @Transactional
    public void removePassword(UUID userId, String currentPassword) {
        Credential credential = credentials.findById(userId)
            .orElseThrow(() -> new NotFoundException("password_not_set", "This account has no password"));
        if (!passwordEncoder.matches(currentPassword, credential.getPasswordHash())) {
            throw new UnauthorizedException("invalid_credentials", "Current password is incorrect");
        }
        if (oauthAccounts.findByUserId(userId).isEmpty()) {
            throw new ConflictException("last_login_method",
                "Set up another login method before removing your password", null);
        }
        credentials.delete(credential);
    }
}
