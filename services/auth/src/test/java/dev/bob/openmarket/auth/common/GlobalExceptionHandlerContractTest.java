package dev.bob.openmarket.auth.common;

import dev.bob.openmarket.auth.admin.AdminService;
import dev.bob.openmarket.auth.config.SecurityConfig;
import dev.bob.openmarket.auth.support.TestSecurityConfig;
import dev.bob.openmarket.auth.token.TokenCookieService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Framework-level errors must come back as the standard envelope, not 500s.
 * The admin controller is just the vehicle here — it has a typed path
 * variable (UUID) and a GET-only collection endpoint, which is everything
 * these cases need.
 */
@WebMvcTest(dev.bob.openmarket.auth.admin.AdminController.class)
@Import({SecurityConfig.class, TokenCookieService.class, TestSecurityConfig.class})
class GlobalExceptionHandlerContractTest {

    @Autowired MockMvc mvc;

    @MockBean AdminService adminService;
    @MockBean ClientIpResolver clientIpResolver;

    private static final String AUTH = "Authorization";

    @Test
    void unknown_path_returns_404_not_found_envelope() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void wrong_method_returns_405_method_not_allowed_envelope() throws Exception {
        // POST against the GET-only admin list endpoint
        mvc.perform(post("/api/v1/admin/users")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.code").value("method_not_allowed"));
    }

    @Test
    void non_uuid_path_variable_returns_400_validation_failed_with_field() throws Exception {
        mvc.perform(get("/api/v1/admin/users/not-a-uuid")
                .header(AUTH, "Bearer " + TestSecurityConfig.tokenWithRoles("moderator")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("validation_failed"))
            .andExpect(jsonPath("$.field").value("id"));
    }
}
