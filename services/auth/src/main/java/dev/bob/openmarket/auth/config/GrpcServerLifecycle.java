package dev.bob.openmarket.auth.config;

import dev.bob.openmarket.auth.grpc.IntrospectionGrpcService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.protobuf.services.HealthStatusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Owns the gRPC server lifecycle (internal IntrospectToken API). Plain
 * grpc-java instead of a starter: three dependencies, one pinned version
 * line, no annotation magic. Plaintext h2c — the port is unpublished and
 * lives on the compose internal network; TLS/mTLS is a documented deferral.
 *
 * <p>The standard health service answers SERVING only while the server is
 * up, which the gateway polls at boot so a cold auth doesn't turn the first
 * requests into 503s.
 */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final IntrospectionGrpcService introspection;
    private final int port;
    private final HealthStatusManager health = new HealthStatusManager();

    private Server server;
    private volatile boolean running;

    public GrpcServerLifecycle(IntrospectionGrpcService introspection,
                               @Value("${grpc.server.port:9090}") int port) {
        this.introspection = introspection;
        this.port = port;
    }

    @Override
    public void start() {
        try {
            server = ServerBuilder.forPort(port)
                .addService(introspection)
                .addService(health.getHealthService())
                .build()
                .start();
        } catch (IOException e) {
            throw new IllegalStateException("gRPC server could not bind port " + port, e);
        }
        setServing(HealthCheckResponse.ServingStatus.SERVING);
        running = true;
        log.info("gRPC server serving on {} (auth internal API)", port);
    }

    @Override
    public void stop() {
        if (server != null) {
            setServing(HealthCheckResponse.ServingStatus.NOT_SERVING);
            server.shutdownNow();
        }
        running = false;
    }

    private void setServing(HealthCheckResponse.ServingStatus status) {
        health.setStatus("", status);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
