package blocklist

import (
	"context"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

// RedisStore adapts go-redis to the Store interface.
type RedisStore struct {
	RDB *redis.Client
}

func (s RedisStore) Set(ctx context.Context, key, val string, ttl time.Duration) error {
	return s.RDB.Set(ctx, key, val, ttl).Err()
}

func (s RedisStore) Delete(ctx context.Context, key string) error {
	return s.RDB.Del(ctx, key).Err()
}

func (s RedisStore) Exists(ctx context.Context, key string) (bool, error) {
	n, err := s.RDB.Exists(ctx, key).Result()
	return n > 0, err
}

// DialRedis connects to REDIS_URL, accepting both the canonical
// redis://host:port form and the bare host:port the compose file passes.
func DialRedis(redisURL string) *redis.Client {
	if !strings.Contains(redisURL, "://") {
		redisURL = "redis://" + redisURL
	}
	opts, err := redis.ParseURL(redisURL)
	if err != nil {
		// Fall back to the plain addr rather than dying at boot — the
		// blocklist is an optimization, and Blocked() fails open anyway.
		return redis.NewClient(&redis.Options{Addr: strings.TrimPrefix(redisURL, "redis://")})
	}
	return redis.NewClient(opts)
}
