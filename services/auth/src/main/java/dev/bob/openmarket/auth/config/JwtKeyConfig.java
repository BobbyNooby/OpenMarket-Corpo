package dev.bob.openmarket.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;

/**
 * Owns the RS256 signing key.
 *
 * <p>Config: {@code jwt.key-path} points at a JSON Web Key file (full RSA JWK,
 * private parts included). If the file is missing a keypair is generated and
 * persisted there, so local dev works out of the box and restarts keep the
 * same key. In production mount a real key at that path — regenerating it
 * invalidates every issued access token.
 *
 * <p>The public half is served at {@code /.well-known/jwks.json} so the Go
 * gateway can validate JWTs without calling this service per request.
 */
@Configuration
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);
    private static final int KEY_SIZE_BITS = 2048;

    @Bean
    public RSAKey rsaSigningKey(JwtProperties props) {
        Path path = Path.of(props.getKeyPath());
        try {
            if (Files.exists(path)) {
                RSAKey key = RSAKey.parse(Files.readString(path));
                log.info("Loaded JWT signing key {} from {}", key.getKeyID(), path);
                return key;
            }
            RSAKey key = generate();
            Files.createDirectories(path.getParent());
            Files.writeString(path, key.toJSONString());
            log.warn("Generated NEW JWT signing key {} at {} — fine for dev, "
                + "mount a real key in production", key.getKeyID(), path);
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize JWT signing key at " + path, e);
        }
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey key) {
        return new ImmutableJWKSet<>(new JWKSet(key));
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * Verifies our own tokens for authenticated endpoints (signature + expiry +
     * iss + aud). The gateway does the same job for the whole fleet using
     * {@code /.well-known/jwks.json} instead.
     */
    @Bean
    public JwtDecoder jwtDecoder(RSAKey key, JwtProperties props) {
        var publicKey = toPublicKey(key);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> validators =
            new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(props.getIssuer()),
                // no JwtAudienceValidator in this Spring Security version — check the claim directly
                new JwtClaimValidator<java.util.List<String>>("aud",
                    aud -> aud != null && aud.contains(props.getAudience())));
        decoder.setJwtValidator(validators);
        return decoder;
    }

    private static java.security.interfaces.RSAPublicKey toPublicKey(RSAKey key) {
        try {
            return key.toRSAPublicKey();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Signing key is not a valid RSA public key", e);
        }
    }

    private static RSAKey generate() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(KEY_SIZE_BITS);
        var pair = gen.generateKeyPair();

        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
            .privateKey(pair.getPrivate())
            .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
            .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
            .keyID(java.util.UUID.randomUUID().toString())
            .build();
    }
}
