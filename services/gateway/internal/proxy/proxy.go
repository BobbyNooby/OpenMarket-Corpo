// Package proxy builds the shared reverse proxies the gateway mounts for
// backend services. One factory, so every upstream gets identical edge
// behavior: forwarded hop semantics, sanitized headers, streaming-friendly
// flushing, and a uniform error envelope.
package proxy

import (
	"log/slog"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"time"

	"github.com/openmarket-corpo/gateway/internal/httpx"
)

// UpstreamHeaderTimeout bounds time-to-first-response-header on proxied
// calls. Without it, an upstream that accepts TCP but stalls before headers
// holds the gateway connection and goroutine forever (zero-value Transport
// = unlimited wait, and the server deliberately has no WriteTimeout so
// streaming can live). Safe for SSE/WS: those send headers immediately;
// after headers land this timeout no longer applies and streaming is
// unbounded. Package var so tests can shrink it.
var UpstreamHeaderTimeout = 30 * time.Second

// identityHeaders are dropped from every inbound request. Nothing behind
// the gateway mints or trusts them today, but if a future service starts
// trusting one, a client must not be able to plant it first.
var identityHeaders = []string{"X-User-Id", "X-Roles", "X-Email", "X-User-Roles"}

// New returns a reverse proxy for target. It is the gateway's edge hop, so
// it OVERWRITES X-Forwarded-For with the peer address — ReverseProxy's
// default would append to a client-supplied value, letting anyone forge the
// first entry and poison downstream client-IP resolution.
func New(target *url.URL, logger *slog.Logger) *httputil.ReverseProxy {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.ResponseHeaderTimeout = UpstreamHeaderTimeout
	p := &httputil.ReverseProxy{
		Transport: transport,
		Rewrite: func(pr *httputil.ProxyRequest) {
			pr.SetURL(target)

			// Overwrite, never append: the gateway is the trust boundary.
			pr.Out.Header.Del("X-Forwarded-For")
			if host, _, err := net.SplitHostPort(pr.In.RemoteAddr); err == nil {
				pr.Out.Header.Set("X-Forwarded-For", host)
			} else {
				pr.Out.Header.Set("X-Forwarded-For", pr.In.RemoteAddr)
			}
			if pr.In.TLS != nil {
				pr.Out.Header.Set("X-Forwarded-Proto", "https")
			} else {
				pr.Out.Header.Set("X-Forwarded-Proto", "http")
			}
			for _, h := range identityHeaders {
				pr.Out.Header.Del(h)
			}
			// The gateway is the authority on forwarding semantics — a
			// client-supplied Forwarded/X-Forwarded-Host would poison any
			// future upstream that starts trusting them.
			pr.Out.Header.Del("X-Forwarded-Host")
			pr.Out.Header.Del("Forwarded")
		},
		// Stream-friendly: SSE/WebSocket-ish responses must not buffer.
		FlushInterval: -1,
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			logger.Warn("upstream error", "target", target.String(), "path", r.URL.Path, "err", err)
			httpx.Error(w, http.StatusBadGateway, "bad_gateway",
				"The service behind this route is unreachable")
		},
	}
	return p
}
