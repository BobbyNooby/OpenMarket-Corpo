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
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sync/errgroup"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"

	"github.com/openmarket-corpo/gateway/internal/authclient"
	authpb "github.com/openmarket-corpo/gateway/internal/authpb"
	"github.com/openmarket-corpo/gateway/internal/blocklist"
	"google.golang.org/grpc"

	kafka "github.com/segmentio/kafka-go"

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

// kafkaReader adapts segmentio/kafka-go to the blocklist.Reader interface.
// One consumer goroutine only: `last` is safe without a lock because the
// consume loop is strictly Next→Apply→Commit.
type kafkaReader struct {
	r    *kafka.Reader
	last kafka.Message
}

func (k *kafkaReader) Next(ctx context.Context) (blocklist.Event, error) {
	m, err := k.r.FetchMessage(ctx)
	if err != nil {
		return blocklist.Event{}, err
	}
	k.last = m
	return blocklist.Event{Topic: m.Topic, Value: m.Value}, nil
}

func (k *kafkaReader) Commit(ctx context.Context, _ blocklist.Event) error {
	return k.r.CommitMessages(ctx, k.last)
}

type ServiceHealth struct {
	Name   string `json:"name"`
	Status string `json:"status"`
	// Deliberately no URL: /health/system is publicly reachable and must not
	// hand anonymous callers the internal service topology.
}

type HealthResponse struct {
	Status   string          `json:"status"`
	Services []ServiceHealth `json:"services"`
}

// healthChecker is the slice of the gRPC health client the prober needs —
// tests substitute a fake instead of dialing a real channel.
type healthChecker interface {
	Check(ctx context.Context, in *healthpb.HealthCheckRequest,
		opts ...grpc.CallOption) (*healthpb.HealthCheckResponse, error)
}

// systemProber answers /health/system. Probes run in parallel (bounded ~1s
// overall) and the result is snapshotted for healthSnapshotTTL: monitors
// polling this route collapse to one probe round per TTL instead of
// amplifying into an internal probe flood per request.
type systemProber struct {
	urls       map[string]string
	names      []string
	deployed   map[string]bool
	authHealth healthChecker
	secret     string
	client     *http.Client

	mu     sync.Mutex
	cached HealthResponse
	code   int
	at     time.Time
	// probing: a refresh is in flight; concurrent callers get the stale
	// snapshot instead of queueing behind the probe (serve-stale-while-
	// revalidate).
	probing bool
}

const healthSnapshotTTL = 5 * time.Second

func newSystemProber(authHealth healthChecker, secret string) *systemProber {
	return &systemProber{
		urls: map[string]string{
			"auth":      envOrDefault("AUTH_URL", "http://localhost:8080"),
			"catalogue": envOrDefault("CATALOGUE_URL", "http://localhost:8081"),
			"messaging": envOrDefault("MESSAGING_URL", "http://localhost:8082"),
			"presence":  envOrDefault("PRESENCE_URL", "http://localhost:8083"),
			"assets":    envOrDefault("ASSETS_URL", "http://localhost:8084"),
			"admin":     envOrDefault("ADMIN_URL", "http://localhost:8085"),
		},
		names:      []string{"auth", "catalogue", "messaging", "presence", "assets", "admin"},
		deployed:   map[string]bool{"auth": true, "catalogue": true, "admin": true},
		authHealth: authHealth,
		secret:     secret,
		client:     &http.Client{Timeout: 2 * time.Second},
		code:       http.StatusServiceUnavailable,
		cached:     HealthResponse{Status: "degraded", Services: []ServiceHealth{}},
	}
}

func (s *systemProber) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	fresh := time.Since(s.at) < healthSnapshotTTL
	if fresh || s.probing {
		// Fresh snapshot — or a concurrent refresh is already in flight.
		// Serve (possibly stale) immediately instead of piling up behind
		// the probe: this endpoint must answer even when upstreams hang.
		hr, code := s.cached, s.code
		s.mu.Unlock()
		writeHealth(w, code, hr)
		return
	}
	s.probing = true
	s.mu.Unlock()

	// Probes run on a background context on purpose: this is cache
	// refresh, not request work. Parenting on r.Context() would let a
	// client disconnect cancel the round and poison the snapshot with an
	// all-unreachable report for a full TTL.
	hr, code := s.probe()

	s.mu.Lock()
	s.cached, s.code, s.at, s.probing = hr, code, time.Now(), false
	s.mu.Unlock()
	writeHealth(w, code, hr)
}

func writeHealth(w http.ResponseWriter, code int, hr HealthResponse) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(hr)
}

func (s *systemProber) probe() (HealthResponse, int) {
	results := make([]ServiceHealth, len(s.names)+1)

	g, gctx := errgroup.WithContext(context.Background())
	ctx, cancel := context.WithTimeout(gctx, time.Second)
	defer cancel()

	for i, name := range s.names {
		g.Go(func() error {
			sh := ServiceHealth{Name: name}
			req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.urls[name]+"/health/ready", nil)
			if err != nil {
				sh.Status = "unreachable"
				results[i] = sh
				return nil
			}
			resp, err := s.client.Do(req)
			if err != nil {
				sh.Status = "unreachable"
				results[i] = sh
				return nil
			}
			resp.Body.Close()
			sh.Status = "degraded"
			if resp.StatusCode == http.StatusOK {
				sh.Status = "healthy"
			}
			results[i] = sh
			return nil
		})
	}

	// Edge authentication channel detail (gRPC), probed in the same round.
	g.Go(func() error {
		sh := ServiceHealth{Name: "auth-grpc", Status: "unreachable"}
		cctx, cancel := context.WithTimeout(ctx, time.Second)
		defer cancel()
		resp, err := s.authHealth.Check(authclient.AppendSecret(cctx, s.secret),
			&healthpb.HealthCheckRequest{})
		if err == nil && resp.GetStatus() == healthpb.HealthCheckResponse_SERVING {
			sh.Status = "healthy"
		}
		results[len(s.names)] = sh
		return nil
	})

	_ = g.Wait()

	allHealthy := true
	// Deployed services must be healthy for the overall report; pending
	// ones are expected to be unreachable and must not degrade it.
	for _, sh := range results[:len(s.names)] {
		if sh.Status != "healthy" && s.deployed[sh.Name] {
			allHealthy = false
		}
	}
	if results[len(s.names)].Status != "healthy" {
		allHealthy = false
	}

	status, code := "degraded", http.StatusServiceUnavailable
	if allHealthy {
		status, code = "healthy", http.StatusOK
	}
	return HealthResponse{Status: status, Services: results}, code
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

	// ── edge blocklist: auth's user.banned events → Redis ─────────
	// Both halves are optional: no Redis or no Kafka just means every
	// request pays the introspection round-trip instead of the cache hit.
	var blocklisted *blocklist.Blocklist
	if redisURL := envOrDefault("REDIS_URL", ""); redisURL != "" {
		rdb := blocklist.DialRedis(redisURL)
		defer rdb.Close()
		blocklisted = blocklist.New(blocklist.RedisStore{RDB: rdb}, logger)
		logger.Info("blocklist enabled", "redis", redisURL)
	} else {
		logger.Info("blocklist disabled (no REDIS_URL) — introspection only")
	}
	var blReader *kafka.Reader
	if brokers := envOrDefault("KAFKA_BROKERS", ""); brokers != "" && blocklisted != nil {
		blReader = kafka.NewReader(kafka.ReaderConfig{
			Brokers: strings.Split(brokers, ","),
			// Group topics: only events with a blocklist meaning today.
			// user.roles_changed awaits its own consumer (events phase).
			GroupTopics: []string{"user.banned", "user.unbanned", "user.deleted"},
			GroupID:     "gateway-blocklist",
			MinBytes:    1,
			MaxBytes:    1 << 20,
		})
		defer blReader.Close()
		logger.Info("blocklist consumer enabled", "brokers", brokers)
	} else {
		logger.Info("blocklist consumer disabled (no KAFKA_BROKERS or REDIS_URL)")
	}

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
		Blocklist:         blocklisted,
		Logger:            logger,
	})

	// ── catalogue: second live upstream ────────────────────────
	catalogueTarget, err := url.Parse(envOrDefault("CATALOGUE_URL", "http://localhost:8081"))
	if err != nil {
		logger.Error("bad CATALOGUE_URL", "err", err)
		os.Exit(1)
	}
	catalogue.Mount(mux, catalogueTarget, logger)

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
	mux.Handle("/health/system", newSystemProber(healthpb.NewHealthClient(conn), internalSecret))
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
	if blReader != nil {
		g.Go(func() error {
			blocklist.Consume(gctx, &kafkaReader{r: blReader}, blocklisted, logger)
			return nil
		})
	}
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
