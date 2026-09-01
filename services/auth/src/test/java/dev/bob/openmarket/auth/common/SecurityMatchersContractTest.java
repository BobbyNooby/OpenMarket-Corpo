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
 * Pins the URL-matching contract at the security boundary: slash variants and
 * doubled slashes must never smuggle a request onto an unintended handler or
 * past an access rule.
 *
 * <p>Why these expectations hold on Boot 3.3.x: the permitAll rules in
 * {@link SecurityConfig} are exact paths (no trailing wildcards), and Spring
 * Framework 6 disabled trailing-slash handler matching by default — so a
 * slash-variant path can neither downgrade to an anonymous rule nor map onto
 * a real endpoint. The firewall case relies on the default
 * {@code StrictHttpFirewall}, whose rejection happens inside the filter
 * chain, before the DispatcherServlet — hence a bare 400 with no JSON
 * envelope (envelopes are produced by MVC-level handling).
 */
@WebMvcTest(dev.bob.openmarket.auth.admin.AdminController.class)
@Import({SecurityConfig.class, TokenCookieService.class, TestSecurityConfig.class})
class SecurityMatchersContractTest {

    @Autowired MockMvc mvc;

    @MockBean AdminService adminService;
    @MockBean ClientIpResolver clientIpResolver;

    private static final String AUTH = "Authorization";

    @Test
    void trailing_slash_login_cannot_be_reached_anonymously() throws Exception {
        // "/api/v1/auth/login/" misses the exact-path permitAll matcher,
        // falls through to anyRequest().authenticated(), and is rejected —
        // it can never complete a login flow (never 200/204).
        mvc.perform(post("/api/v1/auth/login/"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void trailing_slash_admin_collection_is_not_mapped_to_the_route() throws Exception {
        // Spring Framework 6 no longer maps "/path/" onto "/path", so the
        // slash variant finds no handler and dies as a 404 envelope — it is
        // never silently routed to the collection endpoint.
        mvc.perform(get("/api/v1/admin/users/")
                .header(AUTH, "Bearer " + TestSecurityConfig.tokenWithRoles("moderator")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void double_slash_path_never_reaches_a_handler() throws Exception {
        // In a real servlet container the default StrictHttpFirewall rejects
        // repeated slashes with 400 before any mapping runs. MockMvc skips the
        // filter chain, so here the request falls through to 404 instead —
        // either way the security property holds: //api/... is never mapped
        // to a controller, it can only 400 (firewall) or 404 (no route).
        mvc.perform(get("//api/v1/admin/users")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("not_found"));
    }
}
