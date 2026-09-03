// Package middleware authenticates requests at the gateway edge via the
// auth service's IntrospectToken gRPC. It is the edge check, not the only
// one — every upstream still validates the token itself.
package middleware

import (
	"context"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	authpb "github.com/openmarket-corpo/gateway/internal/authpb"
	"github.com/openmarket-corpo/gateway/internal/httpx"
)

// Introspector is the slice of the auth gRPC client the middleware needs —
// tests substitute a fake, production the generated client.
type Introspector interface {
	IntrospectToken(ctx context.Context, in *authpb.IntrospectTokenRequest,
		opts ...grpc.CallOption) (*authpb.IntrospectTokenResponse, error)
}

// Identity is what a valid token resolves to at the edge.
type Identity struct {
	UserID string
	Roles  []string
}

type ctxKey struct{}

// GetIdentity returns the authenticated identity for this request, if the
// middleware established one.
func GetIdentity(ctx context.Context) (Identity, bool) {
	id, ok := ctx.Value(ctxKey{}).(Identity)
	return id, ok
}

// The auth service's permitAll surface, mirrored exactly. Classification
// happens on the cleaned r.URL.Path, NOT via ServeMux patterns — the mux
// would 301-redirect trailing slashes (turning a POST into a GET) and
// matches percent-decoded paths. Misclassifying here is availability-only
// (auth re-validates), but exact keeps the 401s predictable.
var publicExact = map[string]bool{
	"/api/v1/auth/register":         true,
	"/api/v1/auth/login":            true,
	"/api/v1/auth/refresh":          true,
	"/api/v1/auth/logout":           true,
	"/api/v1/auth/verify-email":     true,
	"/api/v1/auth/forgot-password":  true,
	"/api/v1/auth/reset-password":   true,
	"/api/v1/auth/discord":          true,
	"/api/v1/auth/discord/callback": true,
	"/.well-known/jwks.json":        true,
}

// IsPublic reports whether the path is anonymous-accessible at the edge.
// Exact-match only: a prefix rule here would silently exempt the first
// future mount under that prefix from ban enforcement.
func IsPublic(path string) bool {
	return publicExact[path]
}

// extractToken mirrors the auth service's rule: Authorization header wins,
// om_access cookie is the fallback.
func extractToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if strings.HasPrefix(h, "Bearer ") {
		return strings.TrimPrefix(h, "Bearer ")
	}
	if c, err := r.Cookie("om_access"); err == nil {
		return c.Value
	}
	return ""
}

// Auth wraps next with edge authentication. Semantics:
//   - public path            → pass through untouched
//   - no token presented     → pass through (the upstream owns the 401, so
//     the error shape has a single source of truth)
//   - token + active=false   → 401 at the edge (invalid, expired, banned,
//     deleted — no reason to burn an upstream hop)
//   - token + introspection unavailable → 503 (fail closed on protected
//     routes; a flaky check must never degrade into "allow")
func Auth(introspector Introspector, timeout time.Duration, logger *slog.Logger) func(http.Handler) http.Handler {
	cache := newIntrospectionCache(10*time.Second, 10_000)
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if IsPublic(r.URL.Path) {
				next.ServeHTTP(w, r)
				return
			}
			token := extractToken(r)
			if token == "" {
				next.ServeHTTP(w, r)
				return
			}
			if resp, ok := cache.get(token); ok {
				serveWithIdentity(w, r, next, resp)
				return
			}

			ctx, cancel := context.WithTimeout(r.Context(), timeout)
			defer cancel()
			// Fail-fast, deliberately NO WaitForReady: during an auth outage
			// the channel sits in TRANSIENT_FAILURE and WaitForReady would
			// park every uncached request for the full deadline — a pileup,
			// not the documented fast-503 posture. Blip absorption is the
			// verdict cache's job, not the call's.
			resp, err := introspector.IntrospectToken(ctx, &authpb.IntrospectTokenRequest{
				AccessToken: token,
			})
			if err != nil {
				if status.Code(err) == codes.DeadlineExceeded {
					logger.Warn("introspection timed out", "path", r.URL.Path)
				} else {
					logger.Warn("introspection failed", "path", r.URL.Path, "code", status.Code(err))
				}
				httpx.Error(w, http.StatusServiceUnavailable, "service_unavailable",
					"Authentication is temporarily unavailable")
				return
			}
			cache.put(token, resp)
			serveWithIdentity(w, r, next, resp)
		})
	}
}

// serveWithIdentity applies the introspection verdict: inactive tokens die
// at the edge; live ones carry their identity downstream.
func serveWithIdentity(w http.ResponseWriter, r *http.Request, next http.Handler,
	resp *authpb.IntrospectTokenResponse) {
	if !resp.GetActive() {
		httpx.Error(w, http.StatusUnauthorized, "unauthorized",
			"A valid access token is required")
		return
	}
	id := Identity{UserID: resp.GetUserId(), Roles: resp.GetRoles()}
	next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, id)))
}
