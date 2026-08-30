package dev.bob.openmarket.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Discord OAuth2 settings. Endpoint URLs are configurable so tests (and the
 * flow-test script) can point them at a fake Discord server.
 *
 * @see <a href="https://discord.com/developers/docs/topics/oauth2">Discord OAuth2 docs</a>
 */
@ConfigurationProperties(prefix = "discord")
public class DiscordProperties {

    private String clientId = "";
    private String clientSecret = "";
    private String redirectUri = "http://localhost:3000/api/v1/auth/discord/callback";
    private String authorizeUrl = "https://discord.com/oauth2/authorize";
    private String tokenUrl = "https://discord.com/api/oauth2/token";
    private String usersMeUrl = "https://discord.com/api/users/@me";
    private String scopes = "identify email";
    private String successRedirect = "http://localhost:3000/auth/success";
    private String failureRedirect = "http://localhost:3000/auth/failure";

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getAuthorizeUrl() {
        return authorizeUrl;
    }

    public void setAuthorizeUrl(String authorizeUrl) {
        this.authorizeUrl = authorizeUrl;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public void setTokenUrl(String tokenUrl) {
        this.tokenUrl = tokenUrl;
    }

    public String getUsersMeUrl() {
        return usersMeUrl;
    }

    public void setUsersMeUrl(String usersMeUrl) {
        this.usersMeUrl = usersMeUrl;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    public String getSuccessRedirect() {
        return successRedirect;
    }

    public void setSuccessRedirect(String successRedirect) {
        this.successRedirect = successRedirect;
    }

    public String getFailureRedirect() {
        return failureRedirect;
    }

    public void setFailureRedirect(String failureRedirect) {
        this.failureRedirect = failureRedirect;
    }
}
