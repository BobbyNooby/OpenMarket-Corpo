package middleware

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	authpb "github.com/openmarket-corpo/gateway/internal/authpb"
	"github.com/openmarket-corpo/gateway/internal/blocklist"
)

// fakeStore pins the middleware's side of the blocklist contract: a blocked
// sub dies at the edge without an introspection hop; a blocklist outage
// fails open into the normal introspection path.
type fakeStore struct {
	blocked   map[string]bool
	existsErr error
}

func (f *fakeStore) Set(_ context.Context, _, _ string, _ time.Duration) error { return nil }
func (f *fakeStore) Delete(_ context.Context, _ string) error                  { return nil }
func (f *fakeStore) Exists(_ context.Context, key string) (bool, error) {
	if f.existsErr != nil {
		return false, f.existsErr
	}
	return f.blocked[key], nil
}

func tokenWithSub(sub string) string {
	payload, _ := json.Marshal(map[string]string{"sub": sub})
	return base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"RS256"}`)) + "." +
		base64.RawURLEncoding.EncodeToString(payload) + ".forgesig"
}

func Test_blocklisted_sub_is_rejected_without_burning_introspection(t *testing.T) {
	fake := &fakeIntrospector{resp: &authpb.IntrospectTokenResponse{Active: true}}
	store := &fakeStore{blocked: map[string]bool{"om:blocklist:u-banned": true}}
	h := Auth(fake, time.Second, blocklist.New(store, discardLogger()), discardLogger())(
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			t.Fatal("downstream must not be reached for a blocked user")
		}))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/users/me", nil)
	req.Header.Set("Authorization", "Bearer "+tokenWithSub("u-banned"))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("blocked user must get 401, got %d", rec.Code)
	}
	if fake.calls != 0 {
		t.Fatalf("blocked user must not cost an introspection call, got %d", fake.calls)
	}
}

func Test_blocklist_outage_fails_open_into_introspection(t *testing.T) {
	fake := &fakeIntrospector{resp: &authpb.IntrospectTokenResponse{Active: true}}
	store := &fakeStore{existsErr: errors.New("redis down")}
	var seen string
	h := Auth(fake, time.Second, blocklist.New(store, discardLogger()), discardLogger())(
		handler(t, &seen))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/users/me", nil)
	req.Header.Set("Authorization", "Bearer "+tokenWithSub("u-1"))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("blocklist outage must fail open to introspection, got %d", rec.Code)
	}
	if fake.calls != 1 {
		t.Fatalf("introspection must have been consulted, calls=%d", fake.calls)
	}
}

func Test_unblocked_user_flows_through_introspection_normally(t *testing.T) {
	fake := &fakeIntrospector{resp: &authpb.IntrospectTokenResponse{Active: true}}
	store := &fakeStore{blocked: map[string]bool{}}
	var seen string
	h := Auth(fake, time.Second, blocklist.New(store, discardLogger()), discardLogger())(
		handler(t, &seen))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/users/me", nil)
	req.Header.Set("Authorization", "Bearer "+tokenWithSub("u-ok"))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK || fake.calls != 1 {
		t.Fatalf("normal flow broken: code=%d calls=%d", rec.Code, fake.calls)
	}
}
