package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.common.ApiException;
import dev.bob.openmarket.auth.common.ClientIpResolver;
import dev.bob.openmarket.auth.common.ConflictException;
import dev.bob.openmarket.auth.common.UnauthorizedException;
import dev.bob.openmarket.auth.config.DiscordProperties;
import dev.bob.openmarket.auth.oauth.DiscordClient;
import dev.bob.openmarket.auth.oauth.DiscordUser;
import dev.bob.openmarket.auth.oauth.OAuthStateService;
import dev.bob.openmarket.auth.token.AuthResult;
import dev.bob.openmarket.auth.token.TokenCookieService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The Discord authorization-code flow, browser-facing.
 *
 * <p>Redirects (302), not JSON — these endpoints drive a browser. Failures
 * land on the frontend's failure page with {@code ?error=<code>}, per the
 * contract in docs/api.md. The `state` CSRF binding (query param ↔ signed
 * {@code om_oauth} cookie) is validated on every callback.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth")
public class DiscordOAuthController {

    private final AuthService authService;
    private final DiscordClient discord;
    private final OAuthStateService states;
    private final DiscordProperties props;
    private final TokenCookieService cookies;
    private final ClientIpResolver clientIps;

    public DiscordOAuthController(AuthService authService,
                                  DiscordClient discord,
                                  OAuthStateService states,
                                  DiscordProperties props,
                                  TokenCookieService cookies,
                                  ClientIpResolver clientIps) {
        this.authService = authService;
        this.discord = discord;
        this.states = states;
        this.props = props;
        this.cookies = cookies;
        this.clientIps = clientIps;
    }

    @GetMapping("/discord")
    @Operation(summary = "Begin Discord sign-in / sign-up (redirects to Discord)")
    public void start(HttpServletResponse res, HttpServletRequest http) {
        begin(res, http, "login", null);
    }

    @GetMapping("/discord/link")
    @Operation(summary = "Begin linking Discord to the logged-in account")
    public void startLink(Authentication authentication, HttpServletResponse res, HttpServletRequest http) {
        begin(res, http, "link", authentication.getName());
    }

    @GetMapping("/discord/callback")
    @Operation(summary = "Discord redirects here; resolves login / auto-link / signup / link", hidden = true)
    public void callback(@RequestParam(required = false) String code,
                         @RequestParam(required = false) String state,
                         @RequestParam(required = false) String error,
                         HttpServletRequest http,
                         HttpServletResponse res) {
        if (error != null && !error.isBlank()) {
            // user denied the consent screen or Discord errored
            redirect(res, props.getFailureRedirect() + "?error=oauth_failed");
            return;
        }
        OAuthStateService.State validated = states.validate(state, stateCookie(http));
        if (validated == null) {
            redirect(res, failure("oauth_state_mismatch"));
            return;
        }

        try {
            String accessToken = discord.exchangeCode(code);
            DiscordUser discordUser = discord.fetchMe(accessToken);

            if ("link".equals(validated.mode())) {
                authService.linkDiscord(UUID.fromString(validated.sub()), discordUser, accessToken);
            } else {
                AuthResult result = authService.discordLoginOrSignup(discordUser, accessToken,
                    userAgent(http), ip(http));
                cookies.write(res, result.accessToken(), result.refreshToken());
            }
            redirect(res, props.getSuccessRedirect());
        } catch (ApiException e) {
            // oauth_email_required / provider_already_linked → frontend shows it
            redirect(res, failure(e.code()));
        } catch (DiscordClient.OAuthFlowException e) {
            redirect(res, failure("oauth_failed"));
        }
    }

    @DeleteMapping("/connections/discord")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unlink the Discord account (needs another login method left)")
    public void unlink(Authentication authentication) {
        authService.unlinkDiscord(UUID.fromString(authentication.getName()));
    }

    // ── plumbing ─────────────────────────────────────────────

    private void begin(HttpServletResponse res, HttpServletRequest http, String mode, String sub) {
        String state = states.issue(mode, sub);
        res.addHeader("Set-Cookie", stateCookieValue(state));
        redirect(res, discord.buildAuthorizeUrl(state));
    }

    private String stateCookieValue(String state) {
        return cookies.oauthStateCookie(state).toString();
    }

    private String stateCookie(HttpServletRequest http) {
        return cookies.oauthStateFrom(http);
    }

    private String failure(String code) {
        return props.getFailureRedirect() + "?error=" + URLEncoder.encode(code, StandardCharsets.UTF_8);
    }

    private void redirect(HttpServletResponse res, String location) {
        res.setStatus(HttpStatus.FOUND.value());
        res.setHeader("Location", location);
    }

    private static String userAgent(HttpServletRequest http) {
        return http.getHeader("User-Agent");
    }

    private String ip(HttpServletRequest http) {
        return clientIps.resolve(http);
    }
}
