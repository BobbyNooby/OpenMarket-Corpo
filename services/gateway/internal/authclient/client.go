// Package authclient owns the gateway's gRPC channel to the auth service.
package authclient

import (
	"context"
	"log/slog"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/metadata"
)

func appendSecret(ctx context.Context, secret string) context.Context {
	return metadata.AppendToOutgoingContext(ctx, "x-internal-secret", secret)
}

// AppendSecret is the exported form, for handlers outside this package
// that reuse the shared channel (e.g. the system health view).
func AppendSecret(ctx context.Context, secret string) context.Context {
	return appendSecret(ctx, secret)
}

// withInternalSecret attaches the shared internal secret to every unary
// call. Auth's gRPC endpoint rejects calls without it — the endpoint is an
// unauthenticated-by-design oracle, so its one guard is this secret plus
// the unpublished internal network.
func withInternalSecret(secret string) grpc.DialOption {
	return grpc.WithUnaryInterceptor(func(ctx context.Context, method string, req, reply any,
		cc *grpc.ClientConn, invoker grpc.UnaryInvoker, opts ...grpc.CallOption) error {
		ctx = appendSecret(ctx, secret)
		return invoker(ctx, method, req, reply, cc, opts...)
	})
}

// Dial connects to the auth gRPC endpoint. The channel is lazy by default;
// we poll the standard health service up to wait so a cold auth container
// doesn't turn the first user requests into 503s — but an unreachable auth
// is NOT fatal: the gateway still serves health and stub routes, and the
// middleware fails closed on protected paths until auth shows up.
func Dial(ctx context.Context, target, secret string, wait time.Duration, logger *slog.Logger) (*grpc.ClientConn, error) {
	conn, err := grpc.NewClient(target,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		withInternalSecret(secret),
	)
	if err != nil {
		return nil, err
	}

	health := healthpb.NewHealthClient(conn)
	deadline := time.Now().Add(wait)
	for {
		cctx, cancel := context.WithTimeout(ctx, time.Second)
		resp, err := health.Check(appendSecret(cctx, secret), &healthpb.HealthCheckRequest{})
		cancel()
		if err == nil && resp.GetStatus() == healthpb.HealthCheckResponse_SERVING {
			logger.Info("auth gRPC healthy", "target", target)
			return conn, nil
		}
		if time.Now().After(deadline) {
			logger.Warn("auth gRPC not healthy yet — serving degraded", "target", target)
			return conn, nil
		}
		time.Sleep(500 * time.Millisecond)
	}
}
