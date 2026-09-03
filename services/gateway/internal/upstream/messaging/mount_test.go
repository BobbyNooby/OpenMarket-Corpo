package messaging

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

// The mount contract: /api/v1/messaging/* and /ws are edge-checked before
// reaching the upstream; the proxy never appends to client XFF.

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func upstream(t *testing.T, check func(*http.Request)) *httptest.Server {
	t.Helper()
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		check(r)
		w.WriteHeader(http.StatusOK)
	}))
	t.Cleanup(up.Close)
	return up
}

func mounted(t *testing.T, up *httptest.Server) http.Handler {
	t.Helper()
	target, err := url.Parse(up.URL)
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	Mount(mux, Config{Target: target, Logger: discardLogger()})
	return mux
}

// No introspector is configured (nil Introspector) in these mounts: a nil
// introspector means the middleware's no-token pass-through applies — these
// tests pin ROUTING (which paths reach the upstream), not the auth verdict.
// Auth verdicts are pinned in internal/middleware.

func Test_messaging_routes_reach_the_upstream(t *testing.T) {
	var gotPath, gotXFF string
	up := upstream(t, func(r *http.Request) {
		gotPath = r.URL.Path
		gotXFF = r.Header.Get("X-Forwarded-For")
	})
	h := mounted(t, up)

	for _, path := range []string{
		"/api/v1/messaging/conversations",
		"/api/v1/messaging/conversations/unread-count",
		"/ws",
	} {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		req.Header.Set("X-Forwarded-For", "1.2.3.4")
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("%s: want 200 via proxy, got %d", path, rec.Code)
		}
		if gotPath != path {
			t.Fatalf("%s: upstream saw %q", path, gotPath)
		}
		// httptest.NewRequest's peer is 192.0.2.1 — the overwrite property is
		// what matters: the spoofed 1.2.3.4 must be gone, replaced by the peer.
		if gotXFF != "192.0.2.1" {
			t.Fatalf("%s: XFF must be overwritten with the peer, got %q", path, gotXFF)
		}
	}
}

func Test_unknown_messaging_prefix_does_not_leak_into_other_mounts(t *testing.T) {
	up := upstream(t, func(r *http.Request) {
		t.Errorf("upstream must not see %s", r.URL.Path)
	})
	h := mounted(t, up)

	// a sibling namespace must not match the messaging mount
	req := httptest.NewRequest(http.MethodGet, "/api/v1/catalogue/items", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound && !strings.HasPrefix("/api/v1/catalogue/", "/api/v1/messaging") {
		t.Fatalf("sibling namespace must stay unrouted, got %d", rec.Code)
	}
}
