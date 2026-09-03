package dev.bob.openmarket.auth.grpc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import dev.bob.openmarket.auth.domain.Ban;
import dev.bob.openmarket.auth.domain.User;
import dev.bob.openmarket.auth.grpc.v1.IntrospectTokenRequest;
import dev.bob.openmarket.auth.grpc.v1.IntrospectTokenResponse;
import dev.bob.openmarket.auth.repository.BanRepository;
import dev.bob.openmarket.auth.repository.UserRepository;
import dev.bob.openmarket.auth.repository.UserRoleRepository;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The introspection contract: valid+live → active with DB roles; expired,
 * garbage, soft-deleted and banned → active=false. These distinctions are
 * the whole point of the RPC — local JWT validation can't see bans.
 */
@ExtendWith(MockitoExtension.class)
class IntrospectionGrpcServiceTest {

    @Mock UserRepository users;
    @Mock BanRepository bans;
    @Mock UserRoleRepository userRoles;

    private JwtDecoder decoder;
    private JwtEncoder encoder;
    private IntrospectionGrpcService service;

    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
            .privateKey(pair.getPrivate())
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .keyID("test")
            .build();
        JWKSource<SecurityContext> jwks = new ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(key));
        encoder = new NimbusJwtEncoder(jwks);
        decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) pair.getPublic()).build();
        service = new IntrospectionGrpcService(decoder, users, bans, userRoles);
    }

    private String token(String subject, Instant issuedAt, Instant expiresAt) {
        var claims = JwtClaimsSet.builder()
            .issuer("auth")
            .audience(List.of("openmarket"))
            .subject(subject)
            .claim("roles", List.of("user"))
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /** Drains the observer and returns the emitted response. */
    private IntrospectTokenResponse call(String rawToken) {
        var captured = new AtomicReference<IntrospectTokenResponse>();
        StreamObserver<IntrospectTokenResponse> observer = new StreamObserver<>() {
            @Override public void onNext(IntrospectTokenResponse value) { captured.set(value); }
            @Override public void onError(Throwable t) { throw new AssertionError(t); }
            @Override public void onCompleted() {}
        };
        service.introspectToken(
            IntrospectTokenRequest.newBuilder().setAccessToken(rawToken).build(), observer);
        return captured.get();
    }

    @Test
    void store_outage_answers_unavailable_per_contract() {
        // proto contract: infrastructure failure = UNAVAILABLE so consumers
        // fail closed 503. Uncaught, grpc-java would close with UNKNOWN.
        when(users.findByIdAndDeletedAtIsNull(userId))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        var error = new AtomicReference<Throwable>();
        StreamObserver<IntrospectTokenResponse> observer = new StreamObserver<>() {
            @Override public void onNext(IntrospectTokenResponse value) {
                throw new AssertionError("store outage must not answer active");
            }
            @Override public void onError(Throwable t) { error.set(t); }
            @Override public void onCompleted() {}
        };
        service.introspectToken(IntrospectTokenRequest.newBuilder()
            .setAccessToken(token(userId.toString(), Instant.now(), Instant.now().plusSeconds(600)))
            .build(), observer);

        assertThat(error.get()).isNotNull();
        assertThat(io.grpc.Status.fromThrowable(error.get()).getCode())
            .isEqualTo(io.grpc.Status.Code.UNAVAILABLE);
    }

    @Test
    void valid_live_token_is_active_with_db_roles() {
        when(users.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(new User()));
        when(bans.findFirstByUserIdAndLiftedAtIsNullOrderByBannedAtDesc(userId))
            .thenReturn(Optional.empty());
        when(userRoles.findRoleIdsByUserId(userId)).thenReturn(List.of("user", "moderator"));

        var resp = call(token(userId.toString(), Instant.now(), Instant.now().plusSeconds(600)));

        assertThat(resp.getActive()).isTrue();
        assertThat(resp.getUserId()).isEqualTo(userId.toString());
        assertThat(resp.getRolesList()).containsExactly("user", "moderator");
    }

    @Test
    void expired_token_is_not_active() {
        var resp = call(token(userId.toString(), Instant.now().minusSeconds(120), Instant.now().minusSeconds(60)));
        assertThat(resp.getActive()).isFalse();
    }

    @Test
    void garbage_token_is_not_active() {
        var resp = call("not-a-jwt");
        assertThat(resp.getActive()).isFalse();
    }

    @Test
    void soft_deleted_account_is_not_active() {
        when(users.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.empty());
        var resp = call(token(userId.toString(), Instant.now(), Instant.now().plusSeconds(600)));
        assertThat(resp.getActive()).isFalse();
    }

    @Test
    void banned_account_is_not_active_despite_valid_token() {
        when(users.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(new User()));
        // A fresh Ban is active by construction: liftedAt and expiresAt are null.
        Ban ban = new Ban();
        when(bans.findFirstByUserIdAndLiftedAtIsNullOrderByBannedAtDesc(userId))
            .thenReturn(Optional.of(ban));

        var resp = call(token(userId.toString(), Instant.now(), Instant.now().plusSeconds(600)));

        assertThat(resp.getActive()).isFalse();
    }
}
