// Command gateway is OpenMarket's only public entry point: it terminates
// the browser, routes to backend services, and performs edge
// authentication against the auth service over gRPC.
package main

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"syscall"
	"time"

	"golang.org/x/sync/errgroup"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"

	"github.com/openmarket-corpo/gateway/internal/authclient"
	authpb "github.com/openmarket-corpo/gateway/internal/authpb"
	"github.com/openmarket-corpo/gateway/internal/httpx"
	"github.com/openmarket-corpo/gateway/internal/upstream/auth"
	"github.com/openmarket-corpo/gateway/internal/upstream/catalogue"
	"github.com/openmarket-corpo/gateway/internal/upstream/stub"
)

func envOrDefault(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

type ServiceHealth struct {
	Name   string `json:"name"`
	Status string `json:"status"`
	URL    string `json:"url"`
}

type HealthResponse struct {
	Status   string          `json:"status"`
	Services []ServiceHealth `json:"services"`
}

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	authTarget := envOrDefault("AUTH_URL", "http://localhost:8080")
	authGRPC := envOrDefault("AUTH_GRPC_URL", "localhost:9090")
	// Guards the internal IntrospectToken hop; must match auth's
	// GRPC_INTERNAL_SECRET. Compose passes the same value to both.
	internalSecret := envOrDefault("GRPC_INTERNAL_SECRET", "dev-internal-secret")

	// Edge authentication channel. A dead auth degrades protected routes to
	// 503 but never takes the whole gateway down.
	conn, err := authclient.Dial(ctx, authGRPC, internalSecret, 10*time.Second, logger)
	if err != nil {
		logger.Error("auth gRPC dial failed", "err", err)
		os.Exit(1)
	}
	defer conn.Close()
	introspector := authpb.NewAuthServiceClient(conn)

	mux := http.NewServeMux()

	// ── auth: the real upstream ────────────────────────────────
	target, err := url.Parse(authTarget)
	if err != nil {
		logger.Error("bad AUTH_URL", "err", err)
		os.Exit(1)
	}
	auth.Mount(mux, auth.Config{
		Target:            target,
		Introspector:      introspector,
		IntrospectTimeout: 1 * time.Second,
		Logger:            logger,
	})

	// ── catalogue: second live upstream ────────────────────────
	catalogueURL := envOrDefault("CATALOGUE_URL", "http://localhost:8081")
	catalogue.Mount(mux, catalogueURL, logger)

	// ── pending services: mounted, answering 501 until deployed ──
	mux.Handle("/api/v1/messaging/", stub.NotDeployed("messaging"))
	mux.Handle("/api/v1/presence/", stub.NotDeployed("presence"))
	mux.Handle("/api/v1/assets/", stub.NotDeployed("assets"))

	// Unknown API routes answer 404, not the root info JSON. The bare
	// "/api" is registered too, else ServeMux 301-redirects POSTs to "/api/".
	mux.HandleFunc("/api", func(w http.ResponseWriter, r *http.Request) {
		httpx.Error(w, http.StatusNotFound, "not_found", "Unknown API route")
	})
	mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) {
		httpx.Error(w, http.StatusNotFound, "not_found", "Unknown API route")
	})

	mux.HandleFunc("/health/live", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})
	mux.HandleFunc("/health/ready", func(w http.ResponseWriter, r *http.Request) {
		// Deliberately shallow: process-level readiness only. Auth being
		// down must not fail readiness (it would flap containers during
		// restarts); the system view carries the detail.
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "ready"})
	})
	mux.HandleFunc("/health/system", func(w http.ResponseWriter, r *http.Request) {
		healthSystem(healthpb.NewHealthClient(conn), internalSecret, w, r)
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{
			"service": "gateway",
			"status":  "ok",
			"version": "0.2.0",
		})
	})

	port := envOrDefault("PORT", "3000")
	// Body cap at the edge: proxied auth endpoints are small JSON; a flood
	// of giant bodies must die here, not stream into upstreams.
	root := http.MaxBytesHandler(mux, 10<<20)
	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           root,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       60 * time.Second,
		IdleTimeout:       120 * time.Second,
		// No WriteTimeout: streaming mounts (future WS/SSE) must live.
	}

	g, gctx := errgroup.WithContext(ctx)
	g.Go(func() error {
		logger.Info("gateway listening", "port", port, "auth_rest", authTarget, "auth_grpc", authGRPC)
		return srv.ListenAndServe()
	})
	g.Go(func() error {
		<-gctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		return srv.Shutdown(shutdownCtx)
	})
	if err := g.Wait(); err != nil && err != http.ErrServerClosed {
		logger.Error("gateway exited", "err", err)
		os.Exit(1)
	}
	logger.Info("gateway stopped cleanly")
}

func healthSystem(authHealth healthpb.HealthClient, secret string, w http.ResponseWriter, r *http.Request) {
	client := &http.Client{Timeout: 2 * time.Second}
	backendURLs := map[string]string{
		"auth":      envOrDefault("AUTH_URL", "http://localhost:8080"),
		"catalogue": envOrDefault("CATALOGUE_URL", "http://localhost:8081"),
		"messaging": envOrDefault("MESSAGING_URL", "http://localhost:8082"),
		"presence":  envOrDefault("PRESENCE_URL", "http://localhost:8083"),
		"assets":    envOrDefault("ASSETS_URL", "http://localhost:8084"),
		"admin":     envOrDefault("ADMIN_URL", "http://localhost:8085"),
	}
	names := []string{"auth", "catalogue", "messaging", "presence", "assets", "admin"}
	results := make([]ServiceHealth, 0, len(names)+1)

	allHealthy := true
	for _, name := range names {
		sh := ServiceHealth{Name: name, URL: backendURLs[name]}
		resp, err := client.Get(backendURLs[name] + "/health/ready")
		if err != nil {
			sh.Status = "unreachable"
		} else {
			resp.Body.Close()
			if resp.StatusCode == 200 {
				sh.Status = "healthy"
			} else {
				sh.Status = "degraded"
			}
		}
		if sh.Status != "healthy" && name != "auth" {
			// Auth is the only deployed service; pending ones are expected
			// to be unreachable and must not degrade the overall report.
			allHealthy = false
		}
		results = append(results, sh)
	}

	// Edge authentication channel detail (gRPC).
	grpcSh := ServiceHealth{Name: "auth-grpc", Status: "unreachable"}
	cctx, cancel := context.WithTimeout(r.Context(), time.Second)
	resp, err := authHealth.Check(authclient.AppendSecret(cctx, secret), &healthpb.HealthCheckRequest{})
	cancel()
	if err == nil && resp.GetStatus() == healthpb.HealthCheckResponse_SERVING {
		grpcSh.Status = "healthy"
	}
	if grpcSh.Status != "healthy" {
		allHealthy = false
	}
	results = append(results, grpcSh)

	w.Header().Set("Content-Type", "application/json")
	status := "degraded"
	code := http.StatusServiceUnavailable
	if allHealthy {
		status = "healthy"
		code = http.StatusOK
	}
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(HealthResponse{Status: status, Services: results})
}
