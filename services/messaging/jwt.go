package main

import (
	"context"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/big"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

// Verifier resolves a raw RS256 JWT to the caller's user id. Production
// validates against auth's JWKS; tests substitute a fake.
type Verifier interface {
	Verify(ctx context.Context, raw string) (uuid.UUID, error)
}

type jwkSet struct {
	Keys []struct {
		Kid string `json:"kid"`
		Kty string `json:"kty"`
		N   string `json:"n"`
		E   string `json:"e"`
	} `json:"keys"`
}

// JWKSVerifier caches auth's /.well-known/jwks.json and validates RS256
// signatures + expiry locally. Refresh happens lazily when an unknown kid
// shows up (key rotation) and on a max-age timer.
type JWKSVerifier struct {
	jwksURL string
	client  *http.Client

	mu      sync.Mutex
	keys    map[string]*rsa.PublicKey
	fetched time.Time
	maxAge  time.Duration
}

func NewJWKSVerifier(jwksURL string) *JWKSVerifier {
	return &JWKSVerifier{
		jwksURL: jwksURL,
		client:  &http.Client{Timeout: 5 * time.Second},
		keys:    map[string]*rsa.PublicKey{},
		maxAge:  time.Hour,
	}
}

func (v *JWKSVerifier) Verify(ctx context.Context, raw string) (uuid.UUID, error) {
	key, err := v.signingKey(ctx, raw)
	if err != nil {
		return uuid.Nil, err
	}
	claims := jwt.RegisteredClaims{}
	token, err := jwt.ParseWithClaims(raw, &claims, func(t *jwt.Token) (any, error) {
		return key, nil
	}, jwt.WithValidMethods([]string{"RS256"}))
	if err != nil || !token.Valid {
		return uuid.Nil, fmt.Errorf("invalid token")
	}
	if claims.ExpiresAt != nil && time.Now().After(claims.ExpiresAt.Time) {
		return uuid.Nil, fmt.Errorf("token expired")
	}
	id, err := uuid.Parse(claims.Subject)
	if err != nil {
		return uuid.Nil, fmt.Errorf("token has no usable sub")
	}
	return id, nil
}

func (v *JWKSVerifier) signingKey(ctx context.Context, raw string) (*rsa.PublicKey, error) {
	v.mu.Lock()
	defer v.mu.Unlock()

	if len(v.keys) == 0 || time.Since(v.fetched) > v.maxAge {
		if err := v.fetchLocked(ctx); err != nil {
			return nil, err
		}
	}
	kid := kidOf(raw)
	if key, ok := v.keys[kid]; ok {
		return key, nil
	}
	// Unknown kid → auth may have rotated; one forced refresh, then give up.
	if err := v.fetchLocked(ctx); err != nil {
		return nil, err
	}
	if key, ok := v.keys[kid]; ok {
		return key, nil
	}
	return nil, fmt.Errorf("unknown signing key")
}

func (v *JWKSVerifier) fetchLocked(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, v.jwksURL, nil)
	if err != nil {
		return err
	}
	res, err := v.client.Do(req)
	if err != nil {
		return fmt.Errorf("jwks fetch: %w", err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("jwks fetch: status %d", res.StatusCode)
	}
	var set jwkSet
	if err := json.NewDecoder(res.Body).Decode(&set); err != nil {
		return err
	}
	keys := map[string]*rsa.PublicKey{}
	for _, k := range set.Keys {
		if k.Kty != "RSA" {
			continue
		}
		pub, err := rsaFromJWK(k.N, k.E)
		if err != nil {
			continue
		}
		keys[k.Kid] = pub
	}
	if len(keys) == 0 {
		return fmt.Errorf("jwks had no RSA keys")
	}
	v.keys = keys
	v.fetched = time.Now()
	return nil
}

func kidOf(raw string) string {
	parts := strings.SplitN(raw, ".", 3)
	if len(parts) != 3 {
		return ""
	}
	var hdr struct {
		Kid string `json:"kid"`
	}
	if err := json.Unmarshal(jwtSegment(parts[0]), &hdr); err != nil {
		return ""
	}
	return hdr.Kid
}

func jwtSegment(seg string) []byte {
	if b, err := base64.RawURLEncoding.DecodeString(seg); err == nil {
		return b
	}
	return nil
}

func rsaFromJWK(nB64, eB64 string) (*rsa.PublicKey, error) {
	nBytes, err := base64.RawURLEncoding.DecodeString(nB64)
	if err != nil {
		return nil, err
	}
	eBytes, err := base64.RawURLEncoding.DecodeString(eB64)
	if err != nil {
		return nil, err
	}
	e := new(big.Int).SetBytes(eBytes)
	if !e.IsInt64() {
		return nil, fmt.Errorf("exponent too large")
	}
	return &rsa.PublicKey{
		N: new(big.Int).SetBytes(nBytes),
		E: int(e.Int64()),
	}, nil
}
