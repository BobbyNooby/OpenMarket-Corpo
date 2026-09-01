package dev.bob.openmarket.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Auth-service settings under the `auth.*` namespace. Unlike cookie-secure
 * (read via @Value in TokenCookieService), these are bound here so new knobs
 * don't each need their own @Value.
 */
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    /**
     * Comma-separated proxy IPs/CIDRs whose X-Forwarded-For header we trust.
     * Empty (the default) means no proxy is trusted and remoteAddr is always
     * the client IP — trusting this header from an untrusted peer would let
     * anyone forge their address.
     */
    private String trustedProxyIp = "";

    public String getTrustedProxyIp() {
        return trustedProxyIp;
    }

    public void setTrustedProxyIp(String trustedProxyIp) {
        this.trustedProxyIp = trustedProxyIp;
    }
}
