// Package auth mounts the auth service's routes on the gateway. Auth owns
// three route families today — /api/v1/auth, /api/v1/users, /api/v1/admin —
// plus the public JWKS document. Everything rides one reverse proxy; the
// auth middleware decides per request whether the edge checks the token.
package auth

import (
	"log/slog"
	"net/http"
	"net/http/httputil"
	"net/url"
	"time"

	"github.com/openmarket-corpo/gateway/internal/blocklist"
	"github.com/openmarket-corpo/gateway/internal/middleware"
	"github.com/openmarket-corpo/gateway/internal/proxy"
)

// Config carries what Mount needs from the composition root.
type Config struct {
	// Target is the auth service's REST base URL, e.g. http://auth:8080.
	Target *url.URL
	// Introspector is the gRPC client for the edge token check.
	Introspector middleware.Introspector
	// Timeout bounds each introspection call.
	IntrospectTimeout time.Duration
	// Blocklist short-circuits banned/deleted users at the edge; nil is
	// valid (feature off — introspection remains the authority).
	Blocklist *blocklist.Blocklist
	Logger    *slog.Logger
}

// Mount registers auth's route families on mux. Returns the proxy so the
// composition root can reuse it (health checks etc.).
func Mount(mux *http.ServeMux, cfg Config) *httputil.ReverseProxy {
	p := proxy.New(cfg.Target, cfg.Logger)
	edge := middleware.Auth(cfg.Introspector, cfg.IntrospectTimeout, cfg.Blocklist, cfg.Logger)

	// Public document — no edge check, auth serves it anonymously.
	mux.Handle("/.well-known/jwks.json", p)

	authed := edge(p)
	for _, prefix := range []string{"/api/v1/auth/", "/api/v1/users/", "/api/v1/admin/"} {
		mux.Handle(prefix, authed)
	}
	// The bare prefix without trailing slash: ServeMux would not match it
	// with the pattern above, and auth has no such route anyway — still,
	// send it upstream for a truthful 404 instead of the gateway default.
	mux.Handle("/api/v1/auth", authed)
	return p
}
