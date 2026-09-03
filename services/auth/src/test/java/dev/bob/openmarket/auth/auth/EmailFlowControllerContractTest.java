package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.common.ClientIpResolver;
import dev.bob.openmarket.auth.common.RateLimitException;
import dev.bob.openmarket.auth.config.SecurityConfig;
import dev.bob.openmarket.auth.support.TestSecurityConfig;
import dev.bob.openmarket.auth.token.TokenCookieService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the email-flow contract: anonymous confirm endpoints, always-204
 * forgot-password, 202 on email change, and 429 + Retry-After from the rate
 * limiter.
 */
@WebMvcTest(EmailFlowController.class)
@Import({SecurityConfig.class, TokenCookieService.class, TestSecurityConfig.class})
class EmailFlowControllerContractTest {

    @Autowired MockMvc mvc;

    @MockitoBean EmailFlowService emailFlows;
    @MockitoBean ClientIpResolver clientIps; // IP metadata is not under contract here

    private static final String AUTH = "Authorization";

    @Test
    void verifyEmail_is_public_and_returns_204() throws Exception {
        mvc.perform(post("/api/v1/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"raw\"}"))
            .andExpect(status().isNoContent());

        verify(emailFlows).verifyEmail("raw");
    }

    @Test
    void verifyEmail_requires_a_token_body() throws Exception {
        mvc.perform(post("/api/v1/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("validation_failed"))
            .andExpect(jsonPath("$.field").value("token"));
    }

    @Test
    void resend_requires_authentication() throws Exception {
        mvc.perform(post("/api/v1/auth/verify-email/resend"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void resend_returns_204() throws Exception {
        mvc.perform(post("/api/v1/auth/verify-email/resend")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN))
            .andExpect(status().isNoContent());
    }

    @Test
    void forgotPassword_is_public_and_always_204() throws Exception {
        mvc.perform(post("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"sylas@mage-underground.org\"}"))
            .andExpect(status().isNoContent());

        verify(emailFlows).forgotPassword(eq("sylas@mage-underground.org"), any());
    }

    @Test
    void forgotPassword_rejects_oversized_email_with_400() throws Exception {
        // 266 chars > RFC 5321 max (254) — must be validation, not a DB 500
        String oversized = "a".repeat(250) + "@demaciabook.com";

        mvc.perform(post("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + oversized + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("validation_failed"))
            .andExpect(jsonPath("$.field").value("email"));
    }

    @Test
    void resetPassword_is_public_and_returns_204() throws Exception {
        mvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"raw\",\"newPassword\":\"NewPass12345\"}"))
            .andExpect(status().isNoContent());

        verify(emailFlows).resetPassword("raw", "NewPass12345");
    }

    @Test
    void emailChange_returns_202_with_status() throws Exception {
        mvc.perform(post("/api/v1/auth/email/change")
                .header(AUTH, "Bearer " + TestSecurityConfig.ANY_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newEmail\":\"lux2@demaciabook.com\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("verification_sent"));
    }

    @Test
    void rate_limit_surfaces_as_429_with_retry_after() throws Exception {
        doThrow(new RateLimitException(42)).when(emailFlows)
            .forgotPassword(any(), any());

        mvc.perform(post("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"lux@demaciabook.com\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "42"))
            .andExpect(jsonPath("$.code").value("rate_limited"));
    }
}
