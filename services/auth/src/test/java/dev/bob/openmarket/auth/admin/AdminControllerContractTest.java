package dev.bob.openmarket.auth.admin;

import dev.bob.openmarket.auth.common.ConflictException;
import dev.bob.openmarket.auth.common.NotFoundException;
import dev.bob.openmarket.auth.config.SecurityConfig;
import dev.bob.openmarket.auth.support.TestSecurityConfig;
import dev.bob.openmarket.auth.support.TestUsers;
import dev.bob.openmarket.auth.token.TokenCookieService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the moderation contract: the role ladder (owner ⊃ admin ⊃ moderator,
 * enforced by @PreAuthorize against the JWT roles claim) and each endpoint's
 * success/error shape. The stub decoder reads roles from the token suffix.
 */
@WebMvcTest(AdminController.class)
@Import({SecurityConfig.class, TokenCookieService.class, TestSecurityConfig.class})
class AdminControllerContractTest {

    @Autowired MockMvc mvc;

    @MockBean AdminService adminService;

    private static final String ID = TestUsers.USER_ID.toString();

    // ── role ladder ──────────────────────────────────────────

    @Test
    void list_requires_moderator_and_up() throws Exception {
        // anonymous → 401 from the entry point (before role checks)
        mvc.perform(get("/api/v1/admin/users"))
            .andExpect(status().isUnauthorized());

        // user role → authenticated but insufficient → 403
        mvc.perform(get("/api/v1/admin/users")
                .header("Authorization", "Bearer " + TestSecurityConfig.ANY_TOKEN))
            .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/admin/users")
                .header("Authorization", "Bearer " + TestSecurityConfig.tokenWithRoles("moderator")))
            .andExpect(status().isOk());
    }

    @Test
    void admin_role_satisfies_moderator_endpoints_via_hierarchy() throws Exception {
        when(adminService.list(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(new AdminService.PageResult(List.of(), 0, 20, 0));

        mvc.perform(get("/api/v1/admin/users")
                .header("Authorization", "Bearer " + TestSecurityConfig.tokenWithRoles("admin")))
            .andExpect(status().isOk());
    }

    @Test
    void ban_requires_admin() throws Exception {
        mvc.perform(post("/api/v1/admin/users/" + ID + "/ban")
                .header("Authorization", "Bearer " + TestSecurityConfig.tokenWithRoles("moderator"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"r\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void erase_requires_owner() throws Exception {
        mvc.perform(post("/api/v1/admin/users/" + ID + "/erase")
                .header("Authorization", "Bearer " + TestSecurityConfig.tokenWithRoles("admin")))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/admin/users/" + ID + "/erase")
                .header("Authorization", "Bearer " + TestSecurityConfig.tokenWithRoles("owner")))
            .andExpect(status().isAccepted());
    }

    // ── happy paths ──────────────────────────────────────────

    @Test
    void list_returns_paged_result() throws Exception {
        when(adminService.list(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(new AdminService.PageResult(
                List.of(new AdminService.Item(TestUsers.USER_ID, "garen@demaciabook.com",
                    "Garen", true, false, List.of("user"), false)), 0, 20, 1));

        mvc.perform(get("/api/v1/admin/users").queryParam("query", "garen")
                .header("Authorization", "Bearer " + TestSecurityConfig.tokenWithRoles("moderator")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].email").value("garen@demaciabook.com"))
            .andExpect(jsonPath("$.items[0].banned").value(false))
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void ban_passes_reason_and_actor_and_returns_201() throws Exception {
        mvc.perform(post("/api/v1/admin/users/" + ID + "/ban")
                .header("Authorization", "Bearer " + TestSecurityConfig.tokenWithRoles("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"map hacking\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.reason").value("map hacking"));

        verify(adminService).ban(eq(TestUsers.USER_ID), eq(TestUsers.USER_ID), eq("map hacking"), isNull());
    }

    @Test
    void unban_returns_204() throws Exception {
        mvc.perform(post("/api/v1/admin/users/" + ID + "/unban")
                .header("Authorization", "Bearer " + TestSecurityConfig.tokenWithRoles("admin")))
            .andExpect(status().isNoContent());
    }

    @Test
    void unban_without_active_ban_is_404() throws Exception {
        doThrow(new NotFoundException("ban_not_found", "No active ban for this user"))
            .when(adminService).unban(any());

        mvc.perform(post("/api/v1/admin/users/" + ID + "/unban")
                .header("Authorization", "Bearer " + TestSecurityConfig.tokenWithRoles("admin")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ban_not_found"));
    }

    @Test
    void ban_twice_conflicts() throws Exception {
        doThrow(new ConflictException("already_banned", "This user is already banned", null))
            .when(adminService).ban(any(), any(), any(), any());

        mvc.perform(post("/api/v1/admin/users/" + ID + "/ban")
                .header("Authorization", "Bearer " + TestSecurityConfig.tokenWithRoles("admin"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"r\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("already_banned"));
    }

    @Test
    void warn_returns_the_created_warning() throws Exception {
        var warning = new dev.bob.openmarket.auth.domain.Warning();
        warning.setUserId(TestUsers.USER_ID);
        warning.setReason("be nice");
        org.springframework.test.util.ReflectionTestUtils.setField(warning, "id", TestUsers.OTHER_ID);
        org.springframework.test.util.ReflectionTestUtils.setField(warning, "createdAt", Instant.now());
        when(adminService.warn(any(), any(), eq("be nice"))).thenReturn(warning);

        mvc.perform(post("/api/v1/admin/users/" + ID + "/warn")
                .header("Authorization", "Bearer " + TestSecurityConfig.tokenWithRoles("moderator"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"be nice\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.reason").value("be nice"))
            .andExpect(jsonPath("$.userId").value(ID));
    }

    @Test
    void setRoles_returns_the_new_roles() throws Exception {
        when(adminService.setRoles(TestUsers.USER_ID, List.of("user", "moderator")))
            .thenReturn(List.of("user", "moderator"));

        mvc.perform(patch("/api/v1/admin/users/" + ID + "/roles")
                .header("Authorization", "Bearer " + TestSecurityConfig.tokenWithRoles("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"user\",\"moderator\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles[0]").value("user"))
            .andExpect(jsonPath("$.roles[1]").value("moderator"));
    }

    @Test
    void setRoles_with_unknown_role_is_400() throws Exception {
        when(adminService.setRoles(any(), any()))
            .thenThrow(new dev.bob.openmarket.auth.common.BadRequestException(
                "unknown_role", "Unknown role: noxus", "roles"));

        mvc.perform(patch("/api/v1/admin/users/" + ID + "/roles")
                .header("Authorization", "Bearer " + TestSecurityConfig.tokenWithRoles("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"noxus\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("unknown_role"));
    }

    @Test
    void export_returns_the_auth_slice() throws Exception {
        when(adminService.export(TestUsers.USER_ID))
            .thenReturn(java.util.Map.of("user", java.util.Map.of("email", "garen@demaciabook.com")));

        mvc.perform(get("/api/v1/admin/users/" + ID + "/export")
                .header("Authorization", "Bearer " + TestSecurityConfig.tokenWithRoles("admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.email").value("garen@demaciabook.com"));
    }
}
