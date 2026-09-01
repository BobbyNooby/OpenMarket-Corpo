package dev.bob.openmarket.auth.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * OAuth `state` parameter, per Discord's security guidance: the state binds
 * the authorize redirect to this browser, preventing CSRF / login forgery.
 *
 * <p>Format: {@code base64url(payload).base64url(HMAC-SHA256(payload))} where
 * the payload is `{"mode":"login|link","sub":"<uuid for link>","exp":epoch}`.
 * The HMAC key is derived from the RSA signing key via a labeled, domain-
 * separated second SHA-256 round (see {@link #deriveKey}) — stable across
 * restarts, never stored separately, and useless without the private key.
 * The state travels twice: as a query param to Discord and as the httpOnly
 * `om_oauth` cookie; the callback requires both to match, which is exactly
 * the same-origin binding Discord recommends.
 */
@Service
public class OAuthStateService {

    public static final String STATE_COOKIE = "om_oauth";
    private static final long TTL_SECONDS = 600;

    /**
     * Purpose label for the HMAC-key derivation. Same idea as HKDF's "info":
     * key material for one purpose must never equal key material for another,
     * even when both start from the same secret.
     */
    private static final byte[] STATE_KEY_LABEL =
        "openmarket:oauth-state:v1:".getBytes(StandardCharsets.UTF_8);

    private final byte[] hmacKey;
    private final ObjectMapper mapper;
    private final Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder decoder = Base64.getUrlDecoder();

    public OAuthStateService(RSAKey signingKey, ObjectMapper mapper) {
        this.hmacKey = deriveKey(signingKey);
        this.mapper = mapper;
    }

    public record State(String mode, String sub, long exp) {
    }

    public String issue(String mode, String sub) {
        return issueInternal(mode, sub, Instant.now().getEpochSecond() + TTL_SECONDS);
    }

    /** Package-private seam: lets tests mint already-expired states. */
    String issueInternal(String mode, String sub, long epochSecondExpiresAt) {
        try {
            State state = new State(mode, sub, epochSecondExpiresAt);
            String payload = b64.encodeToString(mapper.writeValueAsBytes(state));
            return payload + "." + b64.encodeToString(hmac(payload));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign OAuth state", e);
        }
    }

    /** @return the state if signature + expiry are valid, else null. Never throws. */
    public State validate(String stateParam, String stateCookie) {
        if (stateParam == null || stateCookie == null || !stateParam.equals(stateCookie)) {
            return null; // query param must match the cookie — the CSRF binding
        }
        try {
            String[] parts = stateParam.split("\\.");
            if (parts.length != 2 || !MessageDigest.isEqual(
                hmac(parts[0]), decoder.decode(parts[1]))) {
                return null;
            }
            State state = mapper.readValue(decoder.decode(parts[0]), State.class);
            return state.exp() < Instant.now().getEpochSecond() ? null : state;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] hmac(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
        return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Domain-separated key derivation: {@code SHA-256(label || SHA-256(privateKey))}.
     * The RSA private key's primary job is signing JWTs; hashing its encoding
     * once and using the result directly as the state HMAC key would mean two
     * unrelated purposes share one key derivation, so OAuth-state validity is
     * coupled to JWT key rotation and any future consumer that hashes the same
     * key the same way silently gets the identical HMAC key. The labeled second
     * round binds the output to exactly this purpose (and version), keeping the
     * property that made the derivation attractive: stable across restarts,
     * never stored, and useless without the private key. Package-private so the
     * domain-separation test can pin the exact construction.
     */
    static byte[] deriveKey(RSAKey signingKey) {
        try {
            byte[] base = MessageDigest.getInstance("SHA-256")
                .digest(signingKey.toPrivateKey().getEncoded());
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(STATE_KEY_LABEL);
            return sha.digest(base);
        } catch (Exception e) {
            throw new IllegalStateException("Could not derive OAuth state key", e);
        }
    }
}
