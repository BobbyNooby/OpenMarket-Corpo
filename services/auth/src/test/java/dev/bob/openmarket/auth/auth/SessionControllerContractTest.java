package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.config.SecurityConfig;
import dev.bob.openmarket.auth.support.TestSecurityConfig;
import dev.bob.openmarket.auth.support.TestUsers;
import dev.bob.openmarket.auth.token.RefreshTokenService;
import dev.bob.openmarket.auth.token.TokenCookieService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the session-management contract: listing live device sessions,
 * revoking one (ownership-guarded → 404 for someone else's/unknown), and
 * revoke-all. "Current" comes from the presented om_refresh cookie.
 */
@WebMvcTest(SessionController.class)
@Import({SecurityConfig.class, TokenCookieService.class, TestSecurityConfig.class})
class SessionControllerContractTest {

    @Autowired MockMvc mvc;

    @MockBean RefreshTokenService refreshTokens;

    private static final UUID FAMILY = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void sessions_returns_list_with_current_flag() throws Exception {
        when(refreshTokens.listSessions(eq(TestUsers.USER_ID), any()))
            .thenReturn(List.of(new RefreshTokenService.SessionView(
                FAMILY, "LeagueClient/24.0", "127.0.0.1",
                Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-08T10:00:00Z"), true)));

        mvc.perform(get("/api/v1/auth/sessions").cookie(new Cookie("om_access", TestSecurityConfig.ANY_TOKEN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].familyId").value(FAMILY.toString()))
            .andExpect(jsonPath("$[0].current").value(true))
            .andExpect(jsonPath("$[0].userAgent").value("LeagueClient/24.0"))
            .andExpect(jsonPath("$[0].ipAddress").value("127.0.0.1"));
    }

    @Test
    void sessions_without_token_returns_401() throws Exception {
        mvc.perform(get("/api/v1/auth/sessions"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void revoke_one_session_returns_204() throws Exception {
        when(refreshTokens.revokeFamilyForUser(TestUsers.USER_ID, FAMILY)).thenReturn(true);

        mvc.perform(delete("/api/v1/auth/sessions/" + FAMILY)
                .header("Authorization", "Bearer " + TestSecurityConfig.ANY_TOKEN))
            .andExpect(status().isNoContent());
    }

    @Test
    void revoke_unknown_or_foreign_session_returns_404() throws Exception {
        when(refreshTokens.revokeFamilyForUser(eq(TestUsers.USER_ID), any())).thenReturn(false);

        mvc.perform(delete("/api/v1/auth/sessions/" + FAMILY)
                .header("Authorization", "Bearer " + TestSecurityConfig.ANY_TOKEN))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("session_not_found"));
    }

    @Test
    void revoke_all_returns_204() throws Exception {
        mvc.perform(post("/api/v1/auth/sessions/revoke-all")
                .header("Authorization", "Bearer " + TestSecurityConfig.ANY_TOKEN))
            .andExpect(status().isNoContent());
    }
}
