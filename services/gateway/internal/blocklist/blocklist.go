// Package blocklist is the gateway's edge revocation cache: auth's outbox
// relay publishes user events to Kafka, this package consumes them into
// Redis, and the auth middleware short-circuits blocked users before paying
// for an introspection round-trip.
//
// The blocklist is defence in depth, never the authority — introspection
// re-validates every non-blocked request, and a Redis failure here fails
// OPEN (the request proceeds to the normal introspection path). A banned
// user must never be un-banned by a cache outage; they may simply cost
// auth a lookup.
package blocklist

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
	"time"
)

// Store is the KV surface the blocklist needs — tests substitute a fake,
// production wraps a Redis client.
type Store interface {
	Set(ctx context.Context, key, val string, ttl time.Duration) error
	Delete(ctx context.Context, key string) error
	Exists(ctx context.Context, key string) (bool, error)
}

const keyPrefix = "om:blocklist:"

// Blocklist tracks banned/deleted users by id.
type Blocklist struct {
	store  Store
	logger *slog.Logger
}

func New(store Store, logger *slog.Logger) *Blocklist {
	return &Blocklist{store: store, logger: logger}
}

// Blocked reports whether the user id is blocklisted. Any store error
// answers false: the middleware falls through to introspection.
func (b *Blocklist) Blocked(ctx context.Context, userID string) bool {
	if b == nil || b.store == nil || userID == "" {
		return false
	}
	blocked, err := b.store.Exists(ctx, keyPrefix+userID)
	if err != nil {
		b.logger.Warn("blocklist lookup failed — falling through to introspection",
			"err", err)
		return false
	}
	return blocked
}

// userEvent mirrors contracts/proto/openmarket/events/v1/user_events.proto
// (JSON wire format until the Schema Registry phase).
type userEvent struct {
	UserID    string `json:"userId"`
	ExpiresAt string `json:"expiresAt"` // user.banned only; RFC-3339; absent = permanent
}

// Apply applies one Kafka event to the store. user.banned → block (with a
// TTL when the ban is temporary); user.unbanned / user.deleted → unblock.
// user.roles_changed carries no blocklist meaning and is ignored.
func (b *Blocklist) Apply(ctx context.Context, topic string, payload []byte) error {
	if b == nil || b.store == nil {
		return nil
	}
	var ev userEvent
	if err := json.Unmarshal(payload, &ev); err != nil {
		return fmt.Errorf("blocklist: bad %s payload: %w", topic, err)
	}
	if ev.UserID == "" {
		return fmt.Errorf("blocklist: %s payload has no userId", topic)
	}
	key := keyPrefix + ev.UserID

	switch {
	case topic == "user.banned":
		ttl := time.Duration(0) // permanent
		if ev.ExpiresAt != "" {
			exp, err := time.Parse(time.RFC3339, ev.ExpiresAt)
			if err != nil {
				return fmt.Errorf("blocklist: bad expiresAt in user.banned: %w", err)
			}
			d := time.Until(exp)
			if d <= 0 {
				// Ban already expired — auth's introspection wouldn't enforce
				// it either; blocking here would lock a free user out until
				// an admin bothers to lift the row.
				return nil
			}
			ttl = d
		}
		return b.store.Set(ctx, key, topic, ttl)
	case topic == "user.unbanned", topic == "user.deleted":
		return b.store.Delete(ctx, key)
	default:
		return nil // not ours
	}
}

// SubFromToken extracts the unverified `sub` claim from a JWT. It is only a
// cache key: a forged sub merely misses the blocklist and falls through to
// introspection, which is the authority.
func SubFromToken(token string) string {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return ""
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return ""
	}
	var claims struct {
		Sub string `json:"sub"`
	}
	if err := json.Unmarshal(payload, &claims); err != nil {
		return ""
	}
	return claims.Sub
}
