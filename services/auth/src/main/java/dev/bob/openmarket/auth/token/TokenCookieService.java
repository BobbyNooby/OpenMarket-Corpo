package dev.bob.openmarket.auth.token;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;

/**
 * Token transport. Both tokens travel as httpOnly cookies so the browser
 * never holds raw credentials in JS-accessible storage; the Go gateway
 * (the only public entry) reads them and adds Authorization when proxying.
 *
 * <ul>
 *   <li>{@code om_access}  — JWT, path=/ so the gateway sees it everywhere</li>
 *   <li>{@code om_refresh} — opaque rotation token, path locked to /api/v1/auth</li>
 * </ul>
 *
 * <p>Also doubles as the {@link BearerTokenResolver}: header wins (how the
 * gateway forwards), cookie is the fallback (direct dev testing with curl).
 */
@Component
public class TokenCookieService implements BearerTokenResolver {

    public static final String ACCESS_COOKIE = "om_access";
    public static final String REFRESH_COOKIE = "om_refresh";
    private static final String REFRESH_PATH = "/api/v1/auth";

    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;
    private final boolean secure;

    public TokenCookieService(
        @Value("${jwt.access-ttl-minutes:15}") long accessTtlMinutes,
        @Value("${jwt.refresh-ttl-days:7}") long refreshTtlDays,
        @Value("${auth.cookie-secure:false}") boolean secure) {
        this.accessTtlSeconds = accessTtlMinutes * 60;
        this.refreshTtlSeconds = refreshTtlDays * 24 * 60 * 60;
        this.secure = secure;
    }

    public void write(HttpServletResponse response, String accessToken, String refreshToken) {
        response.addHeader("Set-Cookie", accessCookie(accessToken).toString());
        response.addHeader("Set-Cookie", refreshCookie(refreshToken).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", expiredCookie(ACCESS_COOKIE, "/").toString());
        response.addHeader("Set-Cookie", expiredCookie(REFRESH_COOKIE, REFRESH_PATH).toString());
    }

    private ResponseCookie expiredCookie(String name, String path) {
        return baseCookie(name, "", 0, path);
    }

    private ResponseCookie accessCookie(String value) {
        return baseCookie(ACCESS_COOKIE, value, accessTtlSeconds, "/");
    }

    private ResponseCookie refreshCookie(String value) {
        return baseCookie(REFRESH_COOKIE, value, refreshTtlSeconds, REFRESH_PATH);
    }

    private ResponseCookie baseCookie(String name, String value, long maxAgeSeconds, String path) {
        return ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path(path)
            .maxAge(Duration.ofSeconds(maxAgeSeconds))
            .build();
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        String cookie = cookieValue(request, ACCESS_COOKIE);
        return cookie == null || cookie.isBlank() ? null : cookie;
    }

    /** Reads the refresh token cookie; null when absent (anonymous visitor). */
    public String refreshFrom(HttpServletRequest request) {
        return cookieValue(request, REFRESH_COOKIE);
    }

    /** Short-lived httpOnly carrier for the OAuth `state` (CSRF binding). */
    public ResponseCookie oauthStateCookie(String state) {
        return ResponseCookie.from("om_oauth", state)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/api/v1/auth/discord")
            .maxAge(Duration.ofSeconds(600))
            .build();
    }

    /** The om_oauth cookie value, or null when absent. */
    public String oauthStateFrom(HttpServletRequest request) {
        return cookieValue(request, "om_oauth");
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
            .filter(c -> name.equals(c.getName()))
            .map(Cookie::getValue)
            .findFirst()
            .orElse(null);
    }
}
