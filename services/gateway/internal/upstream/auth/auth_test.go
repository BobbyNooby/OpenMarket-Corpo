package auth

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"

	"google.golang.org/grpc"

	authpb "github.com/openmarket-corpo/gateway/internal/authpb"
	"github.com/openmarket-corpo/gateway/internal/upstream/stub"
)

func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// alwaysActive satisfies middleware.Introspector without a gRPC server.
type alwaysActive struct{ calls int }

func (a *alwaysActive) IntrospectToken(ctx context.Context, in *authpb.IntrospectTokenRequest,
	opts ...grpc.CallOption) (*authpb.IntrospectTokenResponse, error) {
	a.calls++
	return &authpb.IntrospectTokenResponse{Active: true, UserId: "u-1", Roles: []string{"user"}}, nil
}

func Test_mount_covers_auth_route_families(t *testing.T) {
	var hit string
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hit = r.URL.Path
		w.WriteHeader(http.StatusOK)
	}))
	defer up.Close()
	target, _ := url.Parse(up.URL)

	mux := http.NewServeMux()
	intro := &alwaysActive{}
	Mount(mux, Config{Target: target, Introspector: intro, IntrospectTimeout: time.Second, Logger: testLogger()})

	for _, path := range []string{
		"/api/v1/auth/login",     // public — proxied, no edge check
		"/api/v1/auth/refresh",   // public — proxied, no edge check
		"/api/v1/users/me",       // protected — introspected
		"/api/v1/admin/users",    // protected — introspected
		"/.well-known/jwks.json", // public — proxied, no edge check
	} {
		hit = ""
		req := httptest.NewRequest(http.MethodGet, path, nil)
		req.Header.Set("Authorization", "Bearer test-token")
		mux.ServeHTTP(httptest.NewRecorder(), req)
		if hit != path {
			t.Fatalf("%s must proxy to auth (upstream saw %q)", path, hit)
		}
	}
	// Two protected paths, one introspection: the second rode the TTL cache
	// (same token) — routing AND caching verified in one pass.
	if intro.calls != 1 {
		t.Fatalf("protected paths must pass the edge check exactly once (cache), got %d", intro.calls)
	}
}

func Test_stubbed_services_cannot_shadow_auth(t *testing.T) {
	mux := http.NewServeMux()
	var hit string
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hit = r.URL.Path
		w.WriteHeader(http.StatusOK)
	}))
	defer up.Close()
	target, _ := url.Parse(up.URL)
	Mount(mux, Config{Target: target, Introspector: &alwaysActive{}, IntrospectTimeout: time.Second, Logger: testLogger()})
	// The composition root also mounts the pending-service stubs — mirror
	// that so we test the real routing table.
	mux.Handle("/api/v1/catalogue/", stub.NotDeployed("catalogue"))

	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/catalogue/items", nil))
	if rec.Code != http.StatusNotImplemented || hit != "" {
		t.Fatalf("catalogue route must stay a stub (code=%d upstream=%q)", rec.Code, hit)
	}
}
