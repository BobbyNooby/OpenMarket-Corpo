package catalogue

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
)

func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// The catalogue mount is the gateway's second live upstream: no edge
// middleware (catalogue authenticates itself), but every shared proxy
// guarantee must survive — path fidelity, header sanitization, honest 502.
func Test_mount_proxies_path_and_strips_planted_identity_headers(t *testing.T) {
	var gotPath, gotUser, gotXFF, gotXFH string
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotUser = r.Header.Get("X-User-Id")
		gotXFF = r.Header.Get("X-Forwarded-For")
		gotXFH = r.Header.Get("X-Forwarded-Host")
		w.WriteHeader(http.StatusOK)
	}))
	defer up.Close()

	target, err := url.Parse(up.URL)
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	Mount(mux, target, testLogger())

	req := httptest.NewRequest(http.MethodGet, "/api/v1/catalogue/items?limit=5", nil)
	req.Header.Set("X-User-Id", "attacker")
	req.Header.Set("X-Forwarded-For", "1.2.3.4")
	req.Header.Set("X-Forwarded-Host", "evil.example")
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	if gotPath != "/api/v1/catalogue/items" {
		t.Fatalf("upstream path = %q, want /api/v1/catalogue/items", gotPath)
	}
	if gotUser != "" {
		t.Fatalf("planted X-User-Id reached upstream: %q", gotUser)
	}
	if gotXFF == "1.2.3.4" {
		t.Fatal("planted X-Forwarded-For survived (must be overwritten)")
	}
	if gotXFH != "" {
		t.Fatalf("planted X-Forwarded-Host reached upstream: %q", gotXFH)
	}
}

func Test_mount_bare_prefix_is_routed(t *testing.T) {
	var hit bool
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hit = true
		w.WriteHeader(http.StatusNotFound) // catalogue's own truthful 404
	}))
	defer up.Close()

	target, _ := url.Parse(up.URL)
	mux := http.NewServeMux()
	Mount(mux, target, testLogger())

	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/catalogue", nil))
	if !hit {
		t.Fatal("bare /api/v1/catalogue prefix not routed to upstream")
	}
}

func Test_mount_dead_upstream_answers_502_envelope(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	up.Close() // dead on arrival

	target, _ := url.Parse(up.URL)
	mux := http.NewServeMux()
	Mount(mux, target, testLogger())

	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/catalogue/items", nil))
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", rec.Code)
	}
	if body := rec.Body.String(); body == "" {
		t.Fatal("expected a JSON error envelope, got empty body")
	}
}
