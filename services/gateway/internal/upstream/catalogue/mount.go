// Package catalogue mounts the catalogue service on the gateway. Catalogue
// enforces its own authentication (deny-by-default FallbackPolicy +
// IntrospectToken ban checks on mutations), so the gateway mounts it as a
// plain proxy — no edge middleware — while still applying the shared edge
// guarantees (XFF overwrite, identity-header stripping) from internal/proxy.
package catalogue

import (
	"log/slog"
	"net/http"
	"net/http/httputil"
	"net/url"

	"github.com/openmarket-corpo/gateway/internal/proxy"
)

// Mount registers catalogue's routes. Returns the proxy so /health/system
// could reuse it if needed.
func Mount(mux *http.ServeMux, targetURL string, logger *slog.Logger) *httputil.ReverseProxy {
	target, err := url.Parse(targetURL)
	if err != nil {
		logger.Error("bad catalogue URL", "url", targetURL, "err", err)
		return nil
	}
	p := proxy.New(target, logger)
	mux.Handle("/api/v1/catalogue/", p)
	mux.Handle("/api/v1/catalogue", p) // bare prefix — truthful upstream 404
	return p
}
