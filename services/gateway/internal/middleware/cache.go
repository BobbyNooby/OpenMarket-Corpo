package middleware

import (
	"crypto/sha256"
	"sync"
	"time"

	authpb "github.com/openmarket-corpo/gateway/internal/authpb"
)

// introspectionCache bounds the cost of edge authentication: without it,
// every token-bearing request is one RSA-2048 verify plus up to three auth
// DB queries — a single valid token could hammer auth through any protected
// route. TTL keeps revocation latency tiny (a ban lands in the edge within
// ttl seconds; the token TTL was already minutes). Only introspection
// *responses* are cached — never errors, so outages stay fail-closed.
type introspectionCache struct {
	mu      sync.Mutex
	ttl     time.Duration
	max     int
	entries map[[32]byte]cacheEntry
}

type cacheEntry struct {
	resp *authpb.IntrospectTokenResponse
	exp  time.Time
}

func newIntrospectionCache(ttl time.Duration, max int) *introspectionCache {
	return &introspectionCache{
		ttl:     ttl,
		max:     max,
		entries: make(map[[32]byte]cacheEntry),
	}
}

func (c *introspectionCache) key(token string) [32]byte {
	return sha256.Sum256([]byte(token))
}

func (c *introspectionCache) get(token string) (*authpb.IntrospectTokenResponse, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.entries[c.key(token)]
	if !ok || time.Now().After(e.exp) {
		return nil, false
	}
	return e.resp, true
}

func (c *introspectionCache) put(token string, resp *authpb.IntrospectTokenResponse) {
	k := c.key(token)
	now := time.Now()
	c.mu.Lock()
	defer c.mu.Unlock()
	c.entries[k] = cacheEntry{resp: resp, exp: now.Add(c.ttl)}
	if len(c.entries) <= c.max {
		return
	}
	// Over budget: one expired-key sweep, then drop arbitrary keys until
	// under the cap. Exact eviction policy doesn't matter for a flood.
	for key, e := range c.entries {
		if now.After(e.exp) {
			delete(c.entries, key)
		}
	}
	for key := range c.entries {
		if len(c.entries) <= c.max {
			break
		}
		delete(c.entries, key)
	}
}
