package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.common.ClientIpResolver;
import dev.bob.openmarket.auth.config.DiscordProperties;
import dev.bob.openmarket.auth.config.SecurityConfig;
import dev.bob.openmarket.auth.oauth.DiscordClient;
import dev.bob.openmarket.auth.oauth.DiscordUser;
import dev.bob.openmarket.auth.oauth.OAuthStateService;
import dev.bob.openmarket.auth.support.TestSecurityConfig;
import dev.bob.openmarket.auth.support.TestUsers;
import dev.bob.openmarket.auth.token.AuthResult;
import dev.bob.openmarket.auth.token.TokenCookieService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the browser-facing OAuth contract. These endpoints speak in 302s,
 * not JSON — every failure must land on the frontend's failure page with
 * ?error=<code>. The Discord HTTP calls are behind DiscordClient (mocked
 * here; stubbed for real in DiscordClientTest / flow-test).
 */
@WebMvcTest(DiscordOAuthController.class)
@Import({SecurityConfig.class, TokenCookieService.class, TestSecurityConfig.class, DiscordProperties.class})
class DiscordOAuthControllerContractTest {

    @Autowired MockMvc mvc;

    @MockitoBean AuthService authService;
    @MockitoBean DiscordClient discord;
    @MockitoBean OAuthStateService states;
    @MockitoBean ClientIpResolver clientIpResolver;

    private static final String AUTH = "Authorization";

    // ── begin flow ───────────────────────────────────────────

    @Test
    void start_redirects_to_discord_and_sets_the_state_cookie() throws Exception {
        when(states.issue("login", null)).thenReturn("signed-state");
        when(discord.buildAuthorizeUrl("signed-state"))
            .thenReturn("https://discord.com/oauth2/authorize?state=signed-state");

        mvc.perform(get("/api/v1/auth/discord"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location",
                "https://discord.com/oauth2/authorize?state=signed-state"))
            .andExpect(cookie().exists("om_oauth"))
            .andExpect(cookie().httpOnly("om_oauth", true))
            .andExpect(cookie().path("om_oauth", "/api/v1/auth/discord"));

        verify(states).issue("login", null);
    }

    @Test
    void link_requires_authentication() throws Exception {
        mvc.perform(get("/api/v1/auth/discord/link"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void link_issues_state_bound_to_the_logged_in_user() throws Exception {
        when(states.issue("link", TestUsers.USER_ID.toString())).thenReturn("link-state");
        when(discord.buildAuthorizeUrl("link-state")).thenReturn("https://discord.com/authorize?x");

        mvc.perform(get("/api/v1/auth/discord/link").header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN))
            .andExpect(status().isFound());

        verify(states).issue("link", TestUsers.USER_ID.toString());
    }

    // ── callback ─────────────────────────────────────────────

    @Test
    void callback_with_discord_error_redirects_to_failure() throws Exception {
        mvc.perform(get("/api/v1/auth/discord/callback").param("error", "access_denied"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location",
                "http://localhost:3000/auth/failure?error=oauth_failed"));
    }

    @Test
    void callback_with_bad_state_redirects_to_state_mismatch() throws Exception {
        when(states.validate(any(), any())).thenReturn(null);

        mvc.perform(get("/api/v1/auth/discord/callback")
                .param("code", "c").param("state", "forged")
                .cookie(new Cookie("om_oauth", "other")))
            .andExpect(status().isFound())
            .andExpect(header().string("Location",
                "http://localhost:3000/auth/failure?error=oauth_state_mismatch"));
    }

    @Test
    void callback_login_mode_exchanges_code_logs_in_and_sets_cookies() throws Exception {
        when(states.validate("st.ate", "st.ate"))
            .thenReturn(new OAuthStateService.State("login", null, Long.MAX_VALUE));
        when(discord.exchangeCode("the-code")).thenReturn("access-token");
        when(discord.fetchMe("access-token"))
            .thenReturn(new DiscordUser("223749168869212160", "garen", "Garen Crownguard",
                "garen@demaciabook.com", true));
        when(authService.discordLoginOrSignup(any(), eq("access-token"), any(), any()))
            .thenReturn(new AuthResult(TestUsers.user(), "access-jwt", "refresh-raw"));

        mvc.perform(get("/api/v1/auth/discord/callback")
                .param("code", "the-code").param("state", "st.ate")
                .cookie(new Cookie("om_oauth", "st.ate")))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "http://localhost:3000/auth/success"))
            .andExpect(cookie().value("om_access", "access-jwt"))
            .andExpect(cookie().value("om_refresh", "refresh-raw"));

        var user = ArgumentCaptor.forClass(DiscordUser.class);
        verify(authService).discordLoginOrSignup(user.capture(), eq("access-token"), any(), any());
        assertThat(user.getValue().id()).isEqualTo("223749168869212160");
    }

    @Test
    void callback_link_mode_links_to_the_state_subject() throws Exception {
        when(states.validate("st.ate", "st.ate"))
            .thenReturn(new OAuthStateService.State("link", TestUsers.USER_ID.toString(), Long.MAX_VALUE));
        when(discord.exchangeCode("the-code")).thenReturn("access-token");
        when(discord.fetchMe("access-token"))
            .thenReturn(new DiscordUser("223749168869212160", "garen", null, null, null));

        mvc.perform(get("/api/v1/auth/discord/callback")
                .param("code", "the-code").param("state", "st.ate")
                .cookie(new Cookie("om_oauth", "st.ate")))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "http://localhost:3000/auth/success"));

        verify(authService).linkDiscord(eq(TestUsers.USER_ID), any(), eq("access-token"));
    }

    @Test
    void callback_email_required_redirects_with_that_code() throws Exception {
        when(states.validate("st.ate", "st.ate"))
            .thenReturn(new OAuthStateService.State("login", null, Long.MAX_VALUE));
        when(discord.exchangeCode("the-code")).thenReturn("access-token");
        when(discord.fetchMe("access-token"))
            .thenReturn(new DiscordUser("223749168869212160", "garen", null, null, null));
        when(authService.discordLoginOrSignup(any(), any(), any(), any()))
            .thenThrow(new dev.bob.openmarket.auth.common.UnauthorizedException(
                "oauth_email_required", "Your Discord account has no verified email"));

        mvc.perform(get("/api/v1/auth/discord/callback")
                .param("code", "the-code").param("state", "st.ate")
                .cookie(new Cookie("om_oauth", "st.ate")))
            .andExpect(status().isFound())
            .andExpect(header().string("Location",
                "http://localhost:3000/auth/failure?error=oauth_email_required"));
    }

    // ── unlink ───────────────────────────────────────────────

    @Test
    void unlink_returns_204_and_requires_auth() throws Exception {
        mvc.perform(delete("/api/v1/auth/connections/discord")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN))
            .andExpect(status().isNoContent());

        verify(authService).unlinkDiscord(TestUsers.USER_ID);

        mvc.perform(delete("/api/v1/auth/connections/discord"))
            .andExpect(status().isUnauthorized());
    }
}
