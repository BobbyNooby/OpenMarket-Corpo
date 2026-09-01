package dev.bob.openmarket.auth.user;

import dev.bob.openmarket.auth.common.NotFoundException;
import dev.bob.openmarket.auth.config.SecurityConfig;
import dev.bob.openmarket.auth.support.TestSecurityConfig;
import dev.bob.openmarket.auth.support.TestUsers;
import dev.bob.openmarket.auth.token.TokenCookieService;
import dev.bob.openmarket.auth.user.dto.MeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins /api/v1/users/me. The stub JwtDecoder authenticates any request that
 * presents a token (header or om_access cookie) as TestUsers.USER_ID with
 * roles ["user"], so these tests also prove that identity comes from the
 * token's `sub` — never from the request.
 */
@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, TokenCookieService.class, TestSecurityConfig.class})
class UserControllerContractTest {

    @Autowired MockMvc mvc;

    @MockBean UserService userService;

    private static final String AUTH = "Authorization";

    private MeResponse me() {
        return new MeResponse(
            TestUsers.USER_ID, "garen@demaciabook.com", "Garen Crownguard", null, false, List.of("user"),
            new MeResponse.LoginMethods(true, List.of("discord")),
            new MeResponse.Profile("garen", "DEMACIA!", java.util.Map.of("discord", "garen"),
                "#c8aa6e", "en", java.util.Map.of(), null));
    }

    @Test
    void me_returns_200_identity_profile_and_login_methods() throws Exception {
        when(userService.me(TestUsers.USER_ID)).thenReturn(me());

        mvc.perform(get("/api/v1/users/me").header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(TestUsers.USER_ID.toString()))
            .andExpect(jsonPath("$.roles[0]").value("user"))
            .andExpect(jsonPath("$.loginMethods.password").value(true))
            .andExpect(jsonPath("$.loginMethods.providers[0]").value("discord"))
            .andExpect(jsonPath("$.profile.username").value("garen"))
            .andExpect(jsonPath("$.profile.bio").value("DEMACIA!"))
            .andExpect(jsonPath("$.profile.socialLinks.discord").value("garen"));
    }

    @Test
    void me_without_token_returns_401_envelope() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void me_unknown_user_returns_404() throws Exception {
        when(userService.me(TestUsers.USER_ID))
            .thenThrow(new NotFoundException("user_not_found", "User not found"));

        mvc.perform(get("/api/v1/users/me").header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("user_not_found"));
    }

    @Test
    void patch_me_applies_partial_update_and_returns_200() throws Exception {
        when(userService.update(eq(TestUsers.USER_ID), any())).thenReturn(me());

        mvc.perform(patch("/api/v1/users/me")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bio\":\"For Demacia!\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profile.username").value("garen"));
    }

    @Test
    void patch_me_invalid_accent_color_returns_400_with_field() throws Exception {
        mvc.perform(patch("/api/v1/users/me")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accentColor\":\"red\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("validation_failed"))
            .andExpect(jsonPath("$.field").value("accentColor"));
    }

    @Test
    void delete_me_returns_204_and_targets_token_sub() throws Exception {
        mvc.perform(delete("/api/v1/users/me").header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN))
            .andExpect(status().isNoContent());

        verify(userService).delete(TestUsers.USER_ID);
    }

    private static dev.bob.openmarket.auth.user.dto.UpdateMeRequest any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
