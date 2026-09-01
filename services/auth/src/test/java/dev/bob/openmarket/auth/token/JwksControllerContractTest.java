package dev.bob.openmarket.auth.token;

import dev.bob.openmarket.auth.config.SecurityConfig;
import dev.bob.openmarket.auth.support.TestKeys;
import dev.bob.openmarket.auth.support.TestSecurityConfig;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The JWKS endpoint is the trust anchor for the whole fleet — its contract
 * (public, RSA, cacheable) is what the Go gateway depends on.
 */
@WebMvcTest(JwksController.class)
@Import({SecurityConfig.class, TokenCookieService.class, TestSecurityConfig.class, TestKeys.class})
class JwksControllerContractTest {

    @Autowired MockMvc mvc;

    @Test
    void jwks_is_public_and_returns_rsa_key_set() throws Exception {
        mvc.perform(get("/.well-known/jwks.json"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.keys").isArray())
            .andExpect(jsonPath("$.keys", Matchers.hasSize(1)))
            .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
            .andExpect(jsonPath("$.keys[0].use").value("sig"))
            .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
            .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
            // private material must never leak into the public set
            .andExpect(jsonPath("$.keys[0].d").doesNotExist())
            .andExpect(jsonPath("$.keys[0].p").doesNotExist());
    }

    @Test
    void jwks_is_cacheable() throws Exception {
        mvc.perform(get("/.well-known/jwks.json"))
            .andExpect(header().string("Cache-Control", Matchers.containsString("max-age=900")));
    }
}
