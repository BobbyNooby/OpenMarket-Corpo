package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"google.golang.org/grpc"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"
)

type fakeHealth struct{}

func (f *fakeHealth) Check(ctx context.Context, in *healthpb.HealthCheckRequest,
	opts ...grpc.CallOption) (*healthpb.HealthCheckResponse, error) {
	return &healthpb.HealthCheckResponse{Status: healthpb.HealthCheckResponse_SERVING}, nil
}

func Test_health_system_redacts_urls_and_snapshots_for_ttl(t *testing.T) {
	var hits int32
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.WriteHeader(http.StatusOK)
	}))
	defer up.Close()

	p := newSystemProber(&fakeHealth{}, "secret")
	// point every probe at one counting upstream, deployed set covers it
	p.urls = map[string]string{"auth": up.URL, "catalogue": up.URL}
	p.names = []string{"auth", "catalogue"}
	p.deployed = map[string]bool{"auth": true, "catalogue": true}

	rec1 := httptest.NewRecorder()
	p.ServeHTTP(rec1, httptest.NewRequest(http.MethodGet, "/health/system", nil))
	rec2 := httptest.NewRecorder()
	p.ServeHTTP(rec2, httptest.NewRequest(http.MethodGet, "/health/system", nil))

	if got := atomic.LoadInt32(&hits); got != 2 { // 2 services, ONE probe round
		t.Fatalf("snapshot failed: upstream hit %d times across two requests, want 2 (one round)", got)
	}
	var body map[string]any
	if err := json.Unmarshal(rec2.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(rec2.Body.String(), `"url"`) || strings.Contains(rec2.Body.String(), up.URL) {
		t.Fatalf("topology leak: /health/system must not disclose upstream URLs: %s", rec2.Body.String())
	}
	if body["status"] != "healthy" {
		t.Fatalf("all-deployed-healthy must report healthy, got %v", body["status"])
	}
}

func Test_health_system_deployed_down_degrades_but_pending_does_not(t *testing.T) {
	p := newSystemProber(&fakeHealth{}, "secret")
	// deployed service down
	p.urls = map[string]string{"auth": "http://127.0.0.1:1", "catalogue": "http://127.0.0.1:1"}
	p.names = []string{"auth", "catalogue", "messaging"}
	p.deployed = map[string]bool{"auth": true, "catalogue": true}

	rec := httptest.NewRecorder()
	p.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/health/system", nil))
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("deployed-down must degrade to 503, got %d", rec.Code)
	}

	// only PENDING service down → still healthy overall
	p.urls["messaging"] = "http://127.0.0.1:1"
	p.urls["auth"] = "http://127.0.0.1:65530" // placeholder, overwritten below
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer up.Close()
	p.urls["auth"] = up.URL
	p.urls["catalogue"] = up.URL
	p.at = time.Time{} // bust the snapshot

	rec2 := httptest.NewRecorder()
	p.ServeHTTP(rec2, httptest.NewRequest(http.MethodGet, "/health/system", nil))
	if rec2.Code != http.StatusOK {
		t.Fatalf("pending-only outage must NOT degrade the report, got %d", rec2.Code)
	}
}

func Test_health_system_probe_survives_client_disconnect(t *testing.T) {
	var hits int32
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.WriteHeader(http.StatusOK)
	}))
	defer up.Close()

	p := newSystemProber(&fakeHealth{}, "secret")
	p.urls = map[string]string{"auth": up.URL}
	p.names = []string{"auth"}
	p.deployed = map[string]bool{"auth": true}

	// a client that already hung up must NOT poison the snapshot: probes
	// are cache refresh on a background context, not request work
	req := httptest.NewRequest(http.MethodGet, "/health/system", nil)
	cancelled, cancel := context.WithCancel(req.Context())
	cancel()
	p.ServeHTTP(httptest.NewRecorder(), req.WithContext(cancelled))

	if got := atomic.LoadInt32(&hits); got != 1 {
		t.Fatalf("probe never ran (hits=%d) — request context leaked into the refresh", got)
	}
	if p.cached.Status != "healthy" {
		t.Fatalf("disconnected caller poisoned the snapshot: %v", p.cached.Status)
	}
}

func Test_health_system_concurrent_callers_get_stale_not_blocked(t *testing.T) {
	release := make(chan struct{})
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-release // first refresh blocks until the test lets it finish
		w.WriteHeader(http.StatusOK)
	}))
	defer func() { close(release); up.Close() }()

	p := newSystemProber(&fakeHealth{}, "secret")
	p.urls = map[string]string{"auth": up.URL}
	p.names = []string{"auth"}
	p.deployed = map[string]bool{"auth": true}
	// stale snapshot from a previous round
	p.cached = HealthResponse{Status: "healthy", Services: []ServiceHealth{{Name: "auth", Status: "healthy"}}}
	p.code = http.StatusOK

	done := make(chan int, 1)
	go func() {
		rec := httptest.NewRecorder()
		p.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/health/system", nil))
		done <- 0 // just must not block indefinitely
	}()

	// give the first caller time to enter the probe, then answer as a
	// concurrent second caller — it must be served the stale snapshot
	time.Sleep(50 * time.Millisecond)
	second := make(chan int, 1)
	go func() {
		rec := httptest.NewRecorder()
		p.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/health/system", nil))
		second <- rec.Code
	}()

	select {
	case code := <-second:
		if code != http.StatusOK {
			t.Fatalf("concurrent caller must be served the stale snapshot, got %d", code)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("second caller blocked behind the in-flight probe")
	}
}
