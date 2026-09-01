package dev.bob.openmarket.auth.common;

import dev.bob.openmarket.auth.config.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Client-IP extraction. X-Forwarded-For is only honoured when the direct
 * peer ({@code remoteAddr}) matches a configured trusted proxy
 * (`auth.trusted-proxy-ip`, IPs or CIDRs). With no proxies configured — the
 * default — remoteAddr is always used, because honouring a forgeable header
 * would let anyone spoof their IP into session metadata and rate limits.
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    /** IPv6 mapped as IPv4 is the longest legal literal (45 chars). */
    private static final int MAX_IP_LENGTH = 45;

    private final List<TrustedNetwork> trustedProxies;

    public ClientIpResolver(AuthProperties properties) {
        this.trustedProxies = parseTrustedProxies(properties.getTrustedProxyIp());
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddr;
        }
        // First entry = original client; the rest are our own proxies.
        // The literal gate rejects malformed entries (and never touches DNS).
        String candidate = forwarded.split(",")[0].trim();
        return parseLiteral(candidate) != null ? candidate : remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        InetAddress address = parseLiteral(remoteAddr);
        if (address == null) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return trustedProxies.stream().anyMatch(network -> network.matches(bytes));
    }

    /**
     * Parses an IP literal or CIDR range (e.g. "10.0.0.0/8") into a
     * matchable network. Malformed entries are dropped with a warning —
     * a typo must fail closed (trust nobody), not crash startup.
     */
    private static List<TrustedNetwork> parseTrustedProxies(String config) {
        if (config == null || config.isBlank()) {
            return List.of();
        }
        List<TrustedNetwork> networks = new ArrayList<>();
        for (String entry : config.split(",")) {
            String value = entry.trim();
            if (value.isEmpty()) {
                continue;
            }
            int slash = value.indexOf('/');
            if (slash >= 0) {
                InetAddress address = parseLiteral(value.substring(0, slash));
                try {
                    int prefixBits = Integer.parseInt(value.substring(slash + 1));
                    if (address != null && prefixBits >= 0 && prefixBits <= address.getAddress().length * 8) {
                        networks.add(new TrustedNetwork(address.getAddress(), prefixBits));
                    } else {
                        log.warn("Ignoring invalid auth.trusted-proxy-ip entry: '{}'", value);
                    }
                } catch (NumberFormatException badPrefix) {
                    log.warn("Ignoring invalid auth.trusted-proxy-ip entry: '{}'", value);
                }
            } else {
                InetAddress address = parseLiteral(value);
                if (address != null) {
                    networks.add(new TrustedNetwork(address.getAddress(), address.getAddress().length * 8));
                } else {
                    log.warn("Ignoring invalid auth.trusted-proxy-ip entry: '{}'", value);
                }
            }
        }
        return List.copyOf(networks);
    }

    /**
     * InetAddress.getByName resolves hostnames via DNS — unacceptable for
     * attacker-influenced input. The character gate keeps it to literals.
     */
    private static InetAddress parseLiteral(String value) {
        if (value.isEmpty() || value.length() > MAX_IP_LENGTH || !value.matches("[0-9a-fA-F.:]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Network + prefix-length CIDR match. IPv4 never matches IPv6 entries. */
    private record TrustedNetwork(byte[] network, int prefixBits) {

        boolean matches(byte[] candidate) {
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remainderBits = prefixBits % 8;
            if (remainderBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainderBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
