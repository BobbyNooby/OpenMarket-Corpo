package proxy

import (
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
)

func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(testWriter{}, nil))
}

type testWriter struct{}

func (testWriter) Write(p []byte) (int, error) { return len(p), nil }

func upstream(t *testing.T, capture func(*http.Request)) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capture(r)
		w.WriteHeader(http.StatusOK)
	}))
}

func Test_spoofed_xff_is_overwritten_not_appended(t *testing.T) {
	var gotXFF string
	up := upstream(t, func(r *http.Request) { gotXFF = r.Header.Get("X-Forwarded-For") })
	defer up.Close()

	target, _ := url.Parse(up.URL)
	p := New(target, testLogger())
	srv := httptest.NewServer(p)
	defer srv.Close()

	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/v1/users/me", nil)
	// The attacker plants a first entry hoping downstream trusts it.
	req.Header.Set("X-Forwarded-For", "1.2.3.4")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()

	if gotXFF != "127.0.0.1" {
		t.Fatalf("XFF must be overwritten with the real peer (got %q)", gotXFF)
	}
}

func Test_identity_headers_are_stripped(t *testing.T) {
	var gotUser string
	up := upstream(t, func(r *http.Request) { gotUser = r.Header.Get("X-User-Id") })
	defer up.Close()

	target, _ := url.Parse(up.URL)
	srv := httptest.NewServer(New(target, testLogger()))
	defer srv.Close()

	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/x", nil)
	req.Header.Set("X-User-Id", "victim")
	req.Header.Set("X-Roles", "owner")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()

	if gotUser != "" {
		t.Fatalf("planted identity header must be stripped, got %q", gotUser)
	}
}

func Test_upstream_failure_is_502_envelope(t *testing.T) {
	target, _ := url.Parse("http://127.0.0.1:1") // nothing listens here
	srv := httptest.NewServer(New(target, testLogger()))
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/v1/users/me")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusBadGateway {
		t.Fatalf("dead upstream must be 502, got %d", resp.StatusCode)
	}
}
