package dev.bob.openmarket.auth.grpc;

import dev.bob.openmarket.auth.domain.Ban;
import dev.bob.openmarket.auth.repository.BanRepository;
import dev.bob.openmarket.auth.repository.UserRepository;
import dev.bob.openmarket.auth.repository.UserRoleRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;
import dev.bob.openmarket.auth.grpc.v1.AuthServiceGrpc;
import dev.bob.openmarket.auth.grpc.v1.IntrospectTokenRequest;
import dev.bob.openmarket.auth.grpc.v1.IntrospectTokenResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal identity lookup for the gateway's edge authentication. This is
 * the load-bearing gRPC call: the gateway asks "is this token a live
 * person?" and gets an answer that no local JWT check can give — bans and
 * soft-deletes are database state, invisible to signature validation.
 *
 * <p>Semantics: invalid/expired/malformed tokens and dead accounts answer
 * {@code active=false}; infrastructure trouble (DB down, bad request shape)
 * surfaces as a gRPC error so the gateway can 503 instead of 401. Auth
 * still re-validates every forwarded request — this endpoint is the edge
 * check, never the only one.
 */
@Service
public class IntrospectionGrpcService extends AuthServiceGrpc.AuthServiceImplBase {

    private final JwtDecoder jwtDecoder;
    private final UserRepository users;
    private final BanRepository bans;
    private final UserRoleRepository userRoles;

    public IntrospectionGrpcService(JwtDecoder jwtDecoder,
                                    UserRepository users,
                                    BanRepository bans,
                                    UserRoleRepository userRoles) {
        this.jwtDecoder = jwtDecoder;
        this.users = users;
        this.bans = bans;
        this.userRoles = userRoles;
    }

    @Override
    public void introspectToken(IntrospectTokenRequest request,
                                StreamObserver<IntrospectTokenResponse> responseObserver) {
        if (request.getAccessToken().isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                .withDescription("access_token is required")
                .asRuntimeException());
            return;
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(request.getAccessToken());
        } catch (JwtException e) {
            // invalid signature, expired, wrong issuer/audience — all the
            // same answer to an untrusted caller: not active
            responseObserver.onNext(inactive());
            responseObserver.onCompleted();
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            responseObserver.onNext(inactive());
            responseObserver.onCompleted();
            return;
        }

        var user = users.findByIdAndDeletedAtIsNull(userId);
        if (user.isEmpty()) {
            responseObserver.onNext(inactive());
            responseObserver.onCompleted();
            return;
        }

        boolean banned = bans.findFirstByUserIdAndLiftedAtIsNullOrderByBannedAtDesc(userId)
            .filter(b -> b.isActive(Instant.now()))
            .isPresent();
        if (banned) {
            responseObserver.onNext(inactive());
            responseObserver.onCompleted();
            return;
        }

        responseObserver.onNext(IntrospectTokenResponse.newBuilder()
            .setActive(true)
            .setUserId(userId.toString())
            .addAllRoles(userRoles.findRoleIdsByUserId(userId))
            .build());
        responseObserver.onCompleted();
    }

    private IntrospectTokenResponse inactive() {
        return IntrospectTokenResponse.newBuilder().setActive(false).build();
    }
}
