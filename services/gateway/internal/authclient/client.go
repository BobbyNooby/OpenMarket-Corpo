// Package authclient owns the gateway's gRPC channel to the auth service.
package authclient

import (
	"context"
	"log/slog"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"
)

// Dial connects to the auth gRPC endpoint. The channel is lazy by default;
// we poll the standard health service up to wait so a cold auth container
// doesn't turn the first user requests into 503s — but an unreachable auth
// is NOT fatal: the gateway still serves health and stub routes, and the
// middleware fails closed on protected paths until auth shows up.
func Dial(ctx context.Context, target string, wait time.Duration, logger *slog.Logger) (*grpc.ClientConn, error) {
	conn, err := grpc.NewClient(target,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		return nil, err
	}

	health := healthpb.NewHealthClient(conn)
	deadline := time.Now().Add(wait)
	for {
		cctx, cancel := context.WithTimeout(ctx, time.Second)
		resp, err := health.Check(cctx, &healthpb.HealthCheckRequest{})
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
