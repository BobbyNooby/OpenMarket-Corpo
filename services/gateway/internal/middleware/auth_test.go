package middleware

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	authpb "github.com/openmarket-corpo/gateway/internal/authpb"
)

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

type fakeIntrospector struct {
	resp *authpb.IntrospectTokenResponse
	err  error

	calls int
	token string
}

func (f *fakeIntrospector) IntrospectToken(ctx context.Context, in *authpb.IntrospectTokenRequest,
	opts ...grpc.CallOption) (*authpb.IntrospectTokenResponse, error) {
	f.calls++
	f.token = in.GetAccessToken()
	if f.err != nil {
		return nil, f.err
	}
	return f.resp, nil
}

func handler(t *testing.T, seen *string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		*seen = r.Header.Get("Authorization")
		w.WriteHeader(http.StatusOK)
	})
}

func Test_public_paths_bypass_introspection(t *testing.T) {
	fake := &fakeIntrospector{}
	var seen string
	h := Auth(fake, time.Second, discardLogger())(handler(t, &seen))

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", nil)
	h.ServeHTTP(httptest.NewRecorder(), req)

	if fake.calls != 0 {
		t.Fatalf("public path must not introspect, got %d calls", fake.calls)
	}
}

func Test_no_token_forwards_to_upstream_which_owns_the_401(t *testing.T) {
	fake := &fakeIntrospector{}
	var seen string
	h := Auth(fake, time.Second, discardLogger())(handler(t, &seen))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/users/me", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if fake.calls != 0 {
		t.Fatalf("no token must skip introspection, got %d calls", fake.calls)
	}
	if rec.Code != http.StatusOK {
		t.Fatalf("request must be forwarded, got %d", rec.Code)
	}
}

func Test_invalid_token_is_401_at_the_edge(t *testing.T) {
	fake := &fakeIntrospector{resp: &authpb.IntrospectTokenResponse{Active: false}}
	var seen string
	h := Auth(fake, time.Second, discardLogger())(handler(t, &seen))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/users/me", nil)
	req.Header.Set("Authorization", "Bearer garbage")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("inactive token must be 401, got %d", rec.Code)
	}
	if seen != "" {
		t.Fatalf("request must not reach the upstream")
	}
}

func Test_valid_token_sets_identity_and_forwards_original_header(t *testing.T) {
	fake := &fakeIntrospector{resp: &authpb.IntrospectTokenResponse{
		Active: true, UserId: "u-123", Roles: []string{"user"},
	}}
	var seen string
	var gotID Identity
	var gotOK bool
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = r.Header.Get("Authorization")
		gotID, gotOK = GetIdentity(r.Context())
	})
	h := Auth(fake, time.Second, discardLogger())(next)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/users/me", nil)
	req.Header.Set("Authorization", "Bearer good-token")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK || seen != "Bearer good-token" {
		t.Fatalf("valid token must forward unchanged (code=%d auth=%q)", rec.Code, seen)
	}
	if !gotOK || gotID.UserID != "u-123" || len(gotID.Roles) != 1 {
		t.Fatalf("identity not injected: %+v ok=%v", gotID, gotOK)
	}
}

func Test_cookie_fallback_when_no_header(t *testing.T) {
	fake := &fakeIntrospector{resp: &authpb.IntrospectTokenResponse{Active: true, UserId: "u-1"}}
	var seen string
	h := Auth(fake, time.Second, discardLogger())(handler(t, &seen))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/users/me", nil)
	req.AddCookie(&http.Cookie{Name: "om_access", Value: "cookie-token"})
	h.ServeHTTP(httptest.NewRecorder(), req)

	if fake.token != "cookie-token" {
		t.Fatalf("cookie must be the fallback token source, got %q", fake.token)
	}
}

func Test_introspection_unavailable_is_503_fail_closed(t *testing.T) {
	fake := &fakeIntrospector{err: status.Error(codes.Unavailable, "auth down")}
	var seen string
	h := Auth(fake, time.Second, discardLogger())(handler(t, &seen))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/users/me", nil)
	req.Header.Set("Authorization", "Bearer something")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("introspection outage must be 503 fail-closed, got %d", rec.Code)
	}
	if seen != "" {
		t.Fatalf("failed edge check must not reach the upstream")
	}
}

func Test_deadline_is_bounded(t *testing.T) {
	fake := &slowIntrospector{delay: 50 * time.Millisecond}
	h := Auth(fake, 10*time.Millisecond, discardLogger())(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/users/me", nil)
	req.Header.Set("Authorization", "Bearer x")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("timeout must surface as 503, got %d", rec.Code)
	}
}

type slowIntrospector struct{ delay time.Duration }

func (s *slowIntrospector) IntrospectToken(ctx context.Context, in *authpb.IntrospectTokenRequest,
	opts ...grpc.CallOption) (*authpb.IntrospectTokenResponse, error) {
	select {
	case <-time.After(s.delay):
		return &authpb.IntrospectTokenResponse{Active: true}, nil
	case <-ctx.Done():
		return nil, status.Error(codes.DeadlineExceeded, "slow")
	}
}

func Test_exact_public_matching_does_not_leak_into_subpaths(t *testing.T) {
	if IsPublic("/api/v1/auth/login/resend") {
		t.Fatal("subpaths of public routes must not be public")
	}
	if !IsPublic("/api/v1/auth/login") {
		t.Fatal("exact public route must match")
	}
}
