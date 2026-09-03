package proxy

import (
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"
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

func Test_forwarding_headers_are_stripped_and_proto_is_set(t *testing.T) {
	var gotXFH, gotFwd, gotProto string
	up := upstream(t, func(r *http.Request) {
		gotXFH = r.Header.Get("X-Forwarded-Host")
		gotFwd = r.Header.Get("Forwarded")
		gotProto = r.Header.Get("X-Forwarded-Proto")
	})
	defer up.Close()

	target, _ := url.Parse(up.URL)
	srv := httptest.NewServer(New(target, testLogger()))
	defer srv.Close()

	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/x", nil)
	req.Header.Set("X-Forwarded-Host", "evil.example")
	req.Header.Set("Forwarded", "for=1.2.3.4")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()

	if gotXFH != "" || gotFwd != "" {
		t.Fatalf("client forwarding headers must be stripped (XFH=%q Fwd=%q)", gotXFH, gotFwd)
	}
	if gotProto != "http" {
		t.Fatalf("gateway must set X-Forwarded-Proto itself, got %q", gotProto)
	}
}

func Test_hung_upstream_is_502_within_header_timeout(t *testing.T) {
	release := make(chan struct{})
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-release // accept TCP, never send headers: the hang scenario
	}))
	defer func() { close(release); up.Close() }()

	target, _ := url.Parse(up.URL)
	saved := UpstreamHeaderTimeout
	UpstreamHeaderTimeout = 50 * time.Millisecond
	defer func() { UpstreamHeaderTimeout = saved }()

	srv := httptest.NewServer(New(target, testLogger()))
	defer srv.Close()

	done := make(chan int, 1)
	go func() {
		resp, err := http.Get(srv.URL + "/api/v1/catalogue/items")
		if err != nil {
			done <- 0
			return
		}
		defer resp.Body.Close()
		done <- resp.StatusCode
	}()

	select {
	case code := <-done:
		if code != http.StatusBadGateway {
			t.Fatalf("hung upstream must be 502, got %d", code)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("request hung past the header timeout — Transport not applied")
	}
}
