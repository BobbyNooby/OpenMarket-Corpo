package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.common.ConflictException;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Pins /api/v1/auth/credentials: the password-credential contract. */
@WebMvcTest(CredentialController.class)
@Import({SecurityConfig.class, TokenCookieService.class, TestSecurityConfig.class})
class CredentialControllerContractTest {

    @Autowired MockMvc mvc;

    @MockBean AuthService authService;
    @MockBean RefreshTokenService refreshTokens;

    private static final UUID FAMILY = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String AUTH = "Authorization";

    @Test
    void add_returns_201() throws Exception {
        mvc.perform(post("/api/v1/auth/credentials")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"demaciaforever222\"}"))
            .andExpect(status().isCreated());

        verify(authService).addPassword(TestUsers.USER_ID, "demaciaforever222");
    }

    @Test
    void add_already_exists_returns_409() throws Exception {
        org.mockito.Mockito.doThrow(new ConflictException("password_exists", "This account already has a password", null))
            .when(authService).addPassword(any(), any());

        mvc.perform(post("/api/v1/auth/credentials")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"demaciaforever222\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("password_exists"));
    }

    @Test
    void add_short_password_returns_400_with_field() throws Exception {
        mvc.perform(post("/api/v1/auth/credentials")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("validation_failed"))
            .andExpect(jsonPath("$.field").value("password"));
    }

    @Test
    void add_password_over_72_utf8_bytes_returns_400_even_under_128_chars() throws Exception {
        // 36 × "é" (2 bytes) + "a" = 73 bytes, 37 chars — passes @Size(128),
        // must still bounce on the bcrypt boundary (@PasswordBytes)
        String password = "é".repeat(36) + "a";

        mvc.perform(post("/api/v1/auth/credentials")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + password + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("validation_failed"))
            .andExpect(jsonPath("$.field").value("password"));
    }

    @Test
    void change_new_password_over_72_utf8_bytes_returns_400() throws Exception {
        String newPassword = "é".repeat(36) + "a";

        mvc.perform(patch("/api/v1/auth/credentials")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"old1pass99\",\"newPassword\":\"" + newPassword + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("validation_failed"))
            .andExpect(jsonPath("$.field").value("newPassword"));
    }

    @Test
    void change_returns_204_and_keeps_the_calling_devices_session() throws Exception {
        when(refreshTokens.familyOf(eq("raw-refresh"))).thenReturn(FAMILY);

        mvc.perform(patch("/api/v1/auth/credentials")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN)
                .cookie(new Cookie("om_refresh", "raw-refresh"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"old1pass99\",\"newPassword\":\"new1pass99\"}"))
            .andExpect(status().isNoContent());

        // keepFamily comes from the presented refresh cookie — current device survives
        verify(authService).changePassword(TestUsers.USER_ID, "old1pass99", "new1pass99", FAMILY);
    }

    @Test
    void change_without_refresh_cookie_keeps_nothing() throws Exception {
        when(refreshTokens.familyOf(isNull())).thenReturn(null);

        mvc.perform(patch("/api/v1/auth/credentials")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"old1pass99\",\"newPassword\":\"new1pass99\"}"))
            .andExpect(status().isNoContent());

        verify(authService).changePassword(eq(TestUsers.USER_ID), any(), any(), isNull());
    }

    @Test
    void remove_returns_204() throws Exception {
        mvc.perform(delete("/api/v1/auth/credentials")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"demaciaforever222\"}"))
            .andExpect(status().isNoContent());

        verify(authService).removePassword(TestUsers.USER_ID, "demaciaforever222");
    }

    @Test
    void remove_blocked_when_last_method_returns_409() throws Exception {
        org.mockito.Mockito.doThrow(new ConflictException("last_login_method", "You need at least one login method", null))
            .when(authService).removePassword(any(), any());

        mvc.perform(delete("/api/v1/auth/credentials")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"demaciaforever222\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("last_login_method"));
    }

    @Test
    void everything_requires_an_access_token() throws Exception {
        mvc.perform(post("/api/v1/auth/credentials")
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"demaciaforever222\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/auth/credentials")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"a\",\"newPassword\":\"b\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/auth/credentials")
                .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"a\"}"))
            .andExpect(status().isUnauthorized());
    }
}
