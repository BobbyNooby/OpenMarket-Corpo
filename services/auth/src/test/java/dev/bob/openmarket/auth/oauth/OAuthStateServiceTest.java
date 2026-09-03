package dev.bob.openmarket.auth.oauth;

import tools.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The state parameter IS the CSRF defense of the OAuth flow — these tests
 * pin the signature check, the query-param ↔ cookie binding, and expiry.
 */
class OAuthStateServiceTest {

    private OAuthStateService service;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
            .privateKey(pair.getPrivate())
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .keyID(UUID.randomUUID().toString())
            .build();
        service = new OAuthStateService(key, new ObjectMapper());
    }

    @Test
    void roundtrip_issue_then_validate_returns_mode_and_sub() {
        String state = service.issue("link", "11111111-1111-1111-1111-111111111111");

        var validated = service.validate(state, state); // param must equal cookie

        assertThat(validated).isNotNull();
        assertThat(validated.mode()).isEqualTo("link");
        assertThat(validated.sub()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(validated.exp()).isGreaterThan(System.currentTimeMillis() / 1000);
    }

    @Test
    void state_param_and_cookie_must_match() {
        String state = service.issue("login", null);

        assertThat(service.validate(state, state + "x")).isNull();
        assertThat(service.validate(state + "x", state + "x")).isNull();
        assertThat(service.validate(state, null)).isNull();
        assertThat(service.validate(null, state)).isNull();
    }

    @Test
    void tampered_payload_is_rejected() {
        String state = service.issue("login", null);
        String[] parts = state.split("\\.");
        String forgedPayload = parts[0].substring(0, parts[0].length() - 2) + "xx";

        assertThat(service.validate(forgedPayload + "." + parts[1], forgedPayload + "." + parts[1]))
            .isNull();
    }

    @Test
    void tampered_signature_is_rejected() {
        String state = service.issue("login", null);
        String[] parts = state.split("\\.");
        String forgedSig = parts[1].substring(0, parts[1].length() - 2) + "xx";

        assertThat(service.validate(parts[0] + "." + forgedSig, parts[0] + "." + forgedSig))
            .isNull();
    }

    @Test
    void garbage_is_rejected_silently() {
        assertThat(service.validate("garbage", "garbage")).isNull();
        assertThat(service.validate("", "")).isNull();
    }

    @Test
    void expired_state_is_rejected() {
        String expired = service.issueInternal("login", null, 1); // epoch 1 = long past

        assertThat(service.validate(expired, expired)).isNull();
    }

    @Test
    void state_key_is_domain_separated_from_the_raw_key_derivation() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
            .privateKey(pair.getPrivate())
            .build();

        byte[] rawDerivation = MessageDigest.getInstance("SHA-256")
            .digest(pair.getPrivate().getEncoded());
        byte[] label = "openmarket:oauth-state:v1:".getBytes(StandardCharsets.UTF_8);
        byte[] labeledDigest = MessageDigest.getInstance("SHA-256")
            .digest(concat(label, rawDerivation));

        assertThat(OAuthStateService.deriveKey(key))
            .isNotEqualTo(rawDerivation)   // not the raw, unlabeled reuse
            .isEqualTo(labeledDigest);     // exactly the labeled second round
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
