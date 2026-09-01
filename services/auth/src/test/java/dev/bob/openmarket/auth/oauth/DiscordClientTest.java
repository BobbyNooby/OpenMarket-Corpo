package dev.bob.openmarket.auth.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bob.openmarket.auth.config.DiscordProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Talks to a fake Discord API served by MockWebServer, using payloads in the
 * real Discord schemas (snowflake-string id, snake_case global_name,
 * unknown fields that must be ignored).
 */
class DiscordClientTest {

    private MockWebServer discord;
    private DiscordClient client;

    @BeforeEach
    void setUp() throws Exception {
        discord = new MockWebServer();
        discord.start();
        DiscordProperties props = new DiscordProperties();
        props.setClientId("1234567890");
        props.setClientSecret("sekrit");
        props.setRedirectUri("http://localhost:3000/api/v1/auth/discord/callback");
        props.setAuthorizeUrl(discord.url("/oauth2/authorize").toString());
        props.setTokenUrl(discord.url("/api/oauth2/token").toString());
        props.setUsersMeUrl(discord.url("/api/users/@me").toString());
        client = new DiscordClient(props);
    }

    @AfterEach
    void tearDown() throws Exception {
        discord.shutdown();
    }

    /** Real Discord /users/@me payload (User object) + extra future fields. */
    private static final String DISCORD_USER_JSON = """
        {
          "id": "223749168869212160",
          "username": "garen",
          "discriminator": "0",
          "global_name": "Garen Crownguard",
          "avatar": "8342729096ea3675442027381ff50dfe",
          "verified": true,
          "email": "garen@demaciabook.com",
          "flags": 64,
          "premium_type": 0,
          "public_flags": 64,
          "collectibles": {"nameplate": {"sku_id": "2247558840304243311"}}
        }
        """;

    @Test
    void exchangeCode_posts_form_encoded_with_credentials() throws Exception {
        discord.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {"access_token": "an-oauth2-token", "token_type": "Bearer",
                 "expires_in": 604800, "refresh_token": "r", "scope": "identify email"}
                """));

        String token = client.exchangeCode("the-code");

        assertThat(token).isEqualTo("an-oauth2-token");
        RecordedRequest req = discord.takeRequest(1, TimeUnit.SECONDS);
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getHeader("Content-Type")).contains("application/x-www-form-urlencoded");
        String body = req.getBody().readUtf8();
        assertThat(body)
            .contains("grant_type=authorization_code")
            .contains("code=the-code")
            .contains("client_id=1234567890")
            .contains("client_secret=sekrit");
    }

    @Test
    void exchangeCode_maps_http_errors_to_oauth_failed() {
        discord.enqueue(new MockResponse().setResponseCode(400)
            .setBody("{\"error\": \"invalid_grant\"}"));

        assertThatThrownBy(() -> client.exchangeCode("bad-code"))
            .isInstanceOf(DiscordClient.OAuthFlowException.class);
    }

    @Test
    void response_slower_than_the_read_timeout_surfaces_as_oauth_failed() {
        DiscordProperties props = new DiscordProperties();
        props.setClientId("1234567890");
        props.setClientSecret("sekrit");
        props.setRedirectUri("http://localhost:3000/api/v1/auth/discord/callback");
        props.setTokenUrl(discord.url("/api/oauth2/token").toString());
        props.setUsersMeUrl(discord.url("/api/users/@me").toString());
        props.setReadTimeoutMs(100); // tiny read timeout for the test
        DiscordClient impatient = new DiscordClient(props);

        discord.enqueue(new MockResponse()
            .setHeadersDelay(2, TimeUnit.SECONDS) // way past the 100ms read timeout
            .setHeader("Content-Type", "application/json")
            .setBody("{\"access_token\": \"late\"}"));

        assertThatThrownBy(() -> impatient.exchangeCode("the-code"))
            .isInstanceOfSatisfying(DiscordClient.OAuthFlowException.class,
                e -> assertThat(e.getMessage()).contains("oauth_failed"));
    }

    @Test
    void fetchMe_parses_the_real_user_schema_and_ignores_unknown_fields() throws Exception {
        discord.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(DISCORD_USER_JSON));

        DiscordUser user = client.fetchMe("an-oauth2-token");

        assertThat(user.id()).isEqualTo("223749168869212160"); // string, not number!
        assertThat(user.username()).isEqualTo("garen");
        assertThat(user.globalName()).isEqualTo("Garen Crownguard");
        assertThat(user.email()).isEqualTo("garen@demaciabook.com");
        assertThat(user.verified()).isTrue();
        assertThat(user.hasVerifiedEmail()).isTrue();
        assertThat(user.displayName()).isEqualTo("Garen Crownguard");

        RecordedRequest req = discord.takeRequest(1, TimeUnit.SECONDS);
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer an-oauth2-token");
    }

    @Test
    void unverified_email_is_never_trusted() {
        DiscordUser user = new DiscordUser("1", "x", "X", "x@x.dev", false);
        assertThat(user.hasVerifiedEmail()).isFalse();

        DiscordUser noEmail = new DiscordUser("1", "x", "X", null, true);
        assertThat(noEmail.hasVerifiedEmail()).isFalse();

        DiscordUser noGlobalName = new DiscordUser("1", "garen", null, null, null);
        assertThat(noGlobalName.displayName()).isEqualTo("garen");
    }

    @Test
    void buildAuthorizeUrl_contains_the_required_params() {
        DiscordProperties props = new DiscordProperties();
        props.setClientId("1234567890");
        props.setRedirectUri("http://localhost:3000/api/v1/auth/discord/callback");
        DiscordClient standalone = new DiscordClient(props);

        String url = standalone.buildAuthorizeUrl("state-123");

        assertThat(url)
            .startsWith("https://discord.com/oauth2/authorize")
            .contains("response_type=code")
            .contains("client_id=1234567890")
            .contains("scope=identify%20email")                          // space must be encoded
            .contains("redirect_uri=http://localhost:3000/api/v1/auth/discord/callback") // : and / are legal in queries
            .contains("state=state-123")
            .contains("prompt=consent");
    }
}
