// Package stub holds the placeholder mounts for services that exist in the
// architecture but are not deployed yet. Each one answers with a stable
// 501 envelope so the frontend can distinguish "route exists, service
// pending" from "unknown route" (404) and "deployed but broken" (502).
package stub

import (
	"net/http"

	"github.com/openmarket-corpo/gateway/internal/httpx"
)

// NotDeployed returns a handler answering 501 with the service named.
func NotDeployed(service string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		httpx.Error(w, http.StatusNotImplemented, "not_deployed",
			"The "+service+" service is not deployed yet")
	})
}
