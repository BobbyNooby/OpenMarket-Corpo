package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.common.ClientIpResolver;
import dev.bob.openmarket.auth.common.ConflictException;
import dev.bob.openmarket.auth.common.UnauthorizedException;
import dev.bob.openmarket.auth.config.SecurityConfig;
import dev.bob.openmarket.auth.support.TestSecurityConfig;
import dev.bob.openmarket.auth.support.TestUsers;
import dev.bob.openmarket.auth.token.AuthResult;
import dev.bob.openmarket.auth.token.TokenCookieService;
import dev.bob.openmarket.auth.user.UserService;
import dev.bob.openmarket.auth.user.dto.UserResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the /api/v1/auth/* HTTP contract: paths, status codes, the error
 * envelope, and cookie transport. Services are mocked — logic lives in
 * AuthServiceTest. CSRF is disabled in SecurityConfig, so no csrf token is
 * needed despite being a "state-changing" request.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, TokenCookieService.class, TestSecurityConfig.class})
class AuthControllerContractTest {

    @Autowired MockMvc mvc;

    @MockBean AuthService authService;
    @MockBean UserService userService;
    @MockBean ClientIpResolver clientIps; // IP metadata is not under contract here

    private static final String REGISTER_JSON =
        "{\"email\":\"garen@demaciabook.com\",\"password\":\"demaciaforever222\",\"name\":\"Garen Crownguard\"}";

    // ── register ─────────────────────────────────────────────

    @Test
    void register_returns_201_user_and_sets_both_cookies() throws Exception {
        when(authService.register(any(), any(), any()))
            .thenReturn(new AuthResult(TestUsers.user(), "access-token-value", "refresh-token-value"));
        when(authService.rolesOf(any())).thenReturn(List.of("user"));

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(REGISTER_JSON))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.user.email").value("garen@demaciabook.com"))
            .andExpect(jsonPath("$.user.roles[0]").value("user"))
            .andExpect(cookie().exists("om_access"))
            .andExpect(cookie().exists("om_refresh"))
            .andExpect(cookie().httpOnly("om_access", true))
            .andExpect(cookie().maxAge("om_access", 900))          // 15 min
            .andExpect(cookie().maxAge("om_refresh", 604800))      // 7 days
            .andExpect(cookie().path("om_refresh", "/api/v1/auth"));
    }

    @Test
    void register_validation_failure_returns_400_with_field() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"katarina@noxus.gov\",\"password\":\"x\",\"name\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("validation_failed"))
            .andExpect(jsonPath("$.field").exists());
    }

    @Test
    void register_malformed_json_returns_400() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("malformed_json"));
    }

    @Test
    void register_duplicate_email_returns_409_envelope() throws Exception {
        when(authService.register(any(), any(), any()))
            .thenThrow(new ConflictException("email_taken", "An account with this email already exists", "email"));

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(REGISTER_JSON))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("email_taken"))
            .andExpect(jsonPath("$.field").value("email"));
    }

    // ── login ────────────────────────────────────────────────

    @Test
    void login_returns_200_and_sets_cookies() throws Exception {
        when(authService.login(any(), any(), any()))
            .thenReturn(new AuthResult(TestUsers.user(), "a", "r"));
        when(authService.rolesOf(any())).thenReturn(List.of("user"));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"garen@demaciabook.com\",\"password\":\"demaciaforever222\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.name").value("Garen Crownguard"))
            .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("HttpOnly"))));
    }

    @Test
    void login_bad_credentials_returns_401_envelope() throws Exception {
        when(authService.login(any(), any(), any()))
            .thenThrow(new UnauthorizedException("invalid_credentials", "Email or password is incorrect"));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"garen@demaciabook.com\",\"password\":\"forNoxus123\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_credentials"))
            .andExpect(jsonPath("$.field").doesNotExist());
    }

    // ── refresh ──────────────────────────────────────────────

    @Test
    void refresh_reads_refresh_cookie_and_rotates_cookies() throws Exception {
        when(authService.refresh(eq("raw-refresh"), any(), any()))
            .thenReturn(new AuthResult(TestUsers.user(), "new-access", "new-refresh"));
        when(authService.rolesOf(any())).thenReturn(List.of("user"));

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("om_refresh", "raw-refresh")))
            .andExpect(status().isOk())
            .andExpect(cookie().value("om_refresh", "new-refresh"))
            .andExpect(cookie().value("om_access", "new-access"));

        verify(authService).refresh(eq("raw-refresh"), any(), any());
    }

    @Test
    void refresh_without_cookie_returns_401() throws Exception {
        when(authService.refresh(isNull(), any(), any()))
            .thenThrow(new UnauthorizedException("missing_refresh_token", "Refresh token is required"));

        mvc.perform(post("/api/v1/auth/refresh"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("missing_refresh_token"));
    }

    // ── logout ───────────────────────────────────────────────

    @Test
    void logout_returns_204_and_clears_cookies() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("om_refresh", "raw-refresh")))
            .andExpect(status().isNoContent())
            .andExpect(cookie().maxAge("om_access", 0))
            .andExpect(cookie().maxAge("om_refresh", 0));

        verify(authService).logout(eq("raw-refresh"));
    }

    @Test
    void logout_is_public_and_best_effort_without_any_cookies() throws Exception {
        // expired/absent access token must not block logging out
        mvc.perform(post("/api/v1/auth/logout"))
            .andExpect(status().isNoContent())
            .andExpect(cookie().maxAge("om_refresh", 0));

        verify(authService).logout(isNull());
    }
}
