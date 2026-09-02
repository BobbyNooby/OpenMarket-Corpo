package dev.bob.openmarket.auth.config;

import dev.bob.openmarket.auth.grpc.IntrospectionGrpcService;
import dev.bob.openmarket.auth.grpc.InternalSecretInterceptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.ServerInterceptors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Owns the gRPC server lifecycle (internal IntrospectToken API). Plain
 * grpc-java instead of a starter: three dependencies, one pinned version
 * line, no annotation magic. Plaintext h2c — the port is unpublished and
 * lives on the compose internal network; TLS/mTLS is a documented deferral.
 * Calls must carry the shared internal secret (checked by
 * {@link InternalSecretInterceptor}) so a foothold in any other container
 * can't use auth as a token-validity oracle.
 *
 * <p>Bounds: fixed executor (a DB stall must not spawn unbounded threads),
 * 64 KiB max message (tokens are small; garbage is not welcome), capped
 * concurrent calls per connection.
 *
 * <p>The standard health service answers SERVING only while the server is
 * up, which the gateway polls at boot so a cold auth doesn't turn the first
 * requests into 503s.
 */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final IntrospectionGrpcService introspection;
    private final InternalSecretInterceptor secretInterceptor;
    private final int port;
    private final HealthStatusManager health = new HealthStatusManager();
    private final ExecutorService executor = Executors.newFixedThreadPool(32);

    private Server server;
    private volatile boolean running;

    public GrpcServerLifecycle(IntrospectionGrpcService introspection,
                               InternalSecretInterceptor secretInterceptor,
                               @Value("${grpc.server.port:9090}") int port) {
        this.introspection = introspection;
        this.secretInterceptor = secretInterceptor;
        this.port = port;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            server = ServerBuilder.forPort(port)
                .executor(executor)
                .maxInboundMessageSize(64 * 1024)
                .addService(ServerInterceptors.intercept(introspection, secretInterceptor))
                .addService(health.getHealthService())
                .build()
                .start();
        } catch (IOException e) {
            throw new IllegalStateException("gRPC server could not bind port " + port, e);
        }
        health.setStatus("", HealthCheckResponse.ServingStatus.SERVING);
        running = true;
        log.info("gRPC server serving on {} (auth internal API)", port);
    }

    @Override
    public synchronized void stop() {
        if (server != null) {
            health.setStatus("", HealthCheckResponse.ServingStatus.NOT_SERVING);
            server.shutdown();
            try {
                if (!server.awaitTermination(1, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            }
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
