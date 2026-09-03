package dev.bob.openmarket.auth.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The introspection endpoint is an unauthenticated-by-design oracle — it
 * answers "is this token live?" to whoever asks. On the compose network the
 * only intended caller is the gateway, so calls must present the shared
 * internal secret or they get rejected before touching the JWT parser or
 * the database. Constant-time comparison; the secret rides the internal
 * network only and is never the credential that protects user data.
 */
@Component
public class InternalSecretInterceptor implements ServerInterceptor {

    static final Metadata.Key<String> SECRET_KEY =
        Metadata.Key.of("x-internal-secret", Metadata.ASCII_STRING_MARSHALLER);

    private final byte[] expected;

    public InternalSecretInterceptor(
        @Value("${grpc.server.internal-secret:${GRPC_INTERNAL_SECRET:dev-internal-secret}}") String expected,
        org.springframework.core.env.Environment environment) {
        // Same guard catalogue ships: the dev default must never silently
        // survive into a prod-shaped environment. Spring has no profiles in
        // active use here, so SPRING_PROFILES_ACTIVE=prod is the explicit
        // production signal.
        for (String profile : environment.getActiveProfiles()) {
            if (profile.equals("prod") && "dev-internal-secret".equals(expected)) {
                throw new IllegalStateException(
                    "GRPC_INTERNAL_SECRET is unset or still the dev default — refusing to start with the prod profile active");
            }
        }
        this.expected = expected.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String presented = headers.get(SECRET_KEY);
        if (presented == null || !MessageDigest.isEqual(
            expected, presented.getBytes(StandardCharsets.UTF_8))) {
            call.close(Status.PERMISSION_DENIED.withDescription("invalid internal secret"), headers);
            return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
    }
}
