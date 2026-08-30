package dev.bob.openmarket.auth.token;

import com.nimbusds.jose.jwk.RSAKey;
import dev.bob.openmarket.auth.config.JwtProperties;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Signs short-lived RS256 access tokens.
 *
 * <p>Claims: sub (user id), roles (role ids for gateway checks), jti, iat,
 * exp, iss, aud. Anything not needed on every request (email, profile...)
 * is deliberately left out — tokens are unreadable-but-public and stale
 * data in tokens is a bug factory; interested services ask auth or read events.
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final RSAKey signingKey;
    private final JwtProperties props;

    public JwtService(JwtEncoder encoder, RSAKey signingKey, JwtProperties props) {
        this.encoder = encoder;
        this.signingKey = signingKey;
        this.props = props;
    }

    public String issue(UUID userId, List<String> roleIds) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(props.getAccessTtlMinutes() * 60);

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
            .keyId(signingKey.getKeyID())
            .build();

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(props.getIssuer())
            .audience(List.of(props.getAudience()))
            .subject(userId.toString())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .id(UUID.randomUUID().toString())
            .claim("roles", roleIds)
            .build();

        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
