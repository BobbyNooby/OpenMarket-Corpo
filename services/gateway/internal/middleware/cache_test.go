package middleware

import (
	"strconv"
	"testing"
	"time"

	authpb "github.com/openmarket-corpo/gateway/internal/authpb"
)

func Test_cache_round_trips_within_ttl(t *testing.T) {
	c := newIntrospectionCache(50*time.Millisecond, 100)
	resp := &authpb.IntrospectTokenResponse{Active: true, UserId: "u-1"}

	if _, ok := c.get("token-a"); ok {
		t.Fatal("empty cache must miss")
	}
	c.put("token-a", resp)
	got, ok := c.get("token-a")
	if !ok || !got.GetActive() || got.GetUserId() != "u-1" {
		t.Fatalf("cache hit must return the stored response, got %+v ok=%v", got, ok)
	}

	time.Sleep(60 * time.Millisecond)
	if _, ok := c.get("token-a"); ok {
		t.Fatal("expired entries must miss")
	}
}

func Test_cache_never_grows_past_the_cap(t *testing.T) {
	c := newIntrospectionCache(time.Minute, 50)
	resp := &authpb.IntrospectTokenResponse{Active: false}

	for i := 0; i < 500; i++ {
		c.put("token-"+strconv.Itoa(i), resp)
	}

	c.mu.Lock()
	size := len(c.entries)
	c.mu.Unlock()
	if size > 50 {
		t.Fatalf("cache exceeded cap: %d entries", size)
	}
}

func Test_distinct_tokens_are_cached_separately(t *testing.T) {
	c := newIntrospectionCache(time.Minute, 100)
	a := &authpb.IntrospectTokenResponse{Active: true, UserId: "a"}
	b := &authpb.IntrospectTokenResponse{Active: false}

	c.put("tok-a", a)
	c.put("tok-b", b)

	if got, _ := c.get("tok-a"); got.GetUserId() != "a" {
		t.Fatalf("token-a polluted: %+v", got)
	}
	if got, _ := c.get("tok-b"); got.GetActive() {
		t.Fatalf("token-b polluted: %+v", got)
	}
}
