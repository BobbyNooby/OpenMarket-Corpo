// Package messaging mounts the chat service on the gateway: the REST
// surface (/api/v1/messaging/...) and the WebSocket push channel (/ws).
// Messaging validates the caller's JWT itself (RS256 via auth's JWKS);
// the gateway still applies the edge check — ban blocklist + introspection
// — so banned users die before the hop, and the shared proxy guarantees
// (XFF overwrite, identity-header stripping) hold for every mount.
package messaging

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
	Target            *url.URL
	Introspector      middleware.Introspector
	IntrospectTimeout time.Duration
	Blocklist         *blocklist.Blocklist
	Logger            *slog.Logger
}

// Mount registers messaging's routes. Unlike catalogue, messaging is NOT a
// public-by-default surface: every route requires a user, so the edge
// middleware wraps everything.
func Mount(mux *http.ServeMux, cfg Config) *httputil.ReverseProxy {
	p := proxy.New(cfg.Target, cfg.Logger)
	edge := middleware.Auth(cfg.Introspector, cfg.IntrospectTimeout, cfg.Blocklist, cfg.Logger)

	authed := edge(p)
	mux.Handle("/api/v1/messaging/", authed)
	mux.Handle("/api/v1/messaging", authed) // bare prefix — truthful upstream 404
	mux.Handle("/ws", authed)               // upgrade request is a GET — edge checks it too
	return p
}
