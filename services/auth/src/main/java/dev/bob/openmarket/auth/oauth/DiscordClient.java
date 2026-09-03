package dev.bob.openmarket.auth.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.bob.openmarket.auth.config.DiscordProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The three raw Discord calls of the authorization-code grant:
 * build the authorize URL, exchange the code (form-urlencoded! JSON is
 * rejected by Discord), fetch the user. Testable: all URLs come from
 * {@link DiscordProperties} so tests point them at a fake server.
 */
@Component
public class DiscordClient {

    private static final Logger log = LoggerFactory.getLogger(DiscordClient.class);

    /** Discord's access-token response (only the field we need). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken) {
    }

    private final DiscordProperties props;
    private final RestClient rest;

    public DiscordClient(DiscordProperties props) {
        this.props = props;
        // These calls sit inside @Transactional flows — a hanging socket would
        // hold a DB connection hostage, so timeouts are mandatory.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        this.rest = RestClient.builder().requestFactory(factory).build();
    }

    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(props.getAuthorizeUrl())
            .queryParam("response_type", "code")
            .queryParam("client_id", props.getClientId())
            .queryParam("redirect_uri", props.getRedirectUri())
            .queryParam("scope", props.getScopes())
            .queryParam("state", state)
            .queryParam("prompt", "consent")
            .encode() // encodes on build: redirect_uri + the space in "identify email"
            .build()
            .toUriString();
    }

    /** Authorization-code exchange. @return the user's access token. */
    public String exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", props.getRedirectUri());
        form.add("client_id", props.getClientId());
        form.add("client_secret", props.getClientSecret());

        try {
            TokenResponse response = rest.post()
                .uri(props.getTokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
            if (response == null || response.accessToken() == null) {
                throw new IllegalStateException("Discord token response missing access_token");
            }
            return response.accessToken();
        } catch (RestClientException e) {
            log.warn("Discord code exchange failed: {}", e.getMessage());
            throw new OAuthFlowException("oauth_failed", "Discord code exchange failed");
        }
    }

    /** GET /users/@me with the user's bearer token. */
    public DiscordUser fetchMe(String accessToken) {
        try {
            return rest.get()
                .uri(props.getUsersMeUrl())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(DiscordUser.class);
        } catch (RestClientException e) {
            log.warn("Discord /users/@me failed: {}", e.getMessage());
            throw new OAuthFlowException("oauth_failed", "Could not read Discord profile");
        }
    }

    public static class OAuthFlowException extends RuntimeException {
        public OAuthFlowException(String code, String message) {
            super(code + ": " + message);
        }
    }
}
