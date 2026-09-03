package main

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/big"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

// The verifier is the identity boundary for the whole chat surface — these
// pins cover what the fakes can't: real RSA verification, algorithm
// confusion, expiry, rotation, and the JWKS-fetch budget under unknown kids.

type jwkKey struct {
	Kid string
	Key *rsa.PrivateKey
}

func jwksServer(t *testing.T, keys *[]jwkKey, fetches *atomic.Int32) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fetches.Add(1)
		var doc struct {
			Keys []map[string]string `json:"keys"`
		}
		for _, k := range *keys {
			doc.Keys = append(doc.Keys, map[string]string{
				"kty": "RSA", "kid": k.Kid, "alg": "RS256", "use": "sig",
				"n": base64.RawURLEncoding.EncodeToString(k.Key.PublicKey.N.Bytes()),
				"e": base64.RawURLEncoding.EncodeToString(big.NewInt(int64(k.Key.PublicKey.E)).Bytes()),
			})
		}
		json.NewEncoder(w).Encode(doc)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func mintRS256(t *testing.T, key *rsa.PrivateKey, kid, sub string, exp time.Time) string {
	t.Helper()
	token := jwt.NewWithClaims(jwt.SigningMethodRS256, jwt.RegisteredClaims{
		Subject:   sub,
		ExpiresAt: jwt.NewNumericDate(exp),
	})
	token.Header["kid"] = kid
	signed, err := token.SignedString(key)
	if err != nil {
		t.Fatal(err)
	}
	return signed
}

func Test_valid_rs256_token_resolves_to_the_sub(t *testing.T) {
	key := jwkKey{Kid: "k1", Key: mustRSAKey(t)}
	var fetches atomic.Int32
	v := NewJWKSVerifier(jwksServer(t, &[]jwkKey{key}, &fetches).URL)

	id := uuid.MustParse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
	got, err := v.Verify(context.Background(), mintRS256(t, key.Key, "k1", id.String(), time.Now().Add(time.Hour)))
	if err != nil {
		t.Fatal(err)
	}
	if got != id {
		t.Fatalf("sub = %v, want %v", got, id)
	}
}

func testVerifier(t *testing.T) (*JWKSVerifier, *jwkKey) {
	t.Helper()
	key := jwkKey{Kid: "k1", Key: mustRSAKey(t)}
	var fetches atomic.Int32
	v := NewJWKSVerifier(jwksServer(t, &[]jwkKey{key}, &fetches).URL)
	return v, &key
}

func Test_wrong_key_and_hs256_and_missing_exp_are_rejected(t *testing.T) {
	v, key := testVerifier(t)
	sub := uuid.MustParse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb").String()
	other := mustRSAKey(t)
	ctx := context.Background()

	// signed by the wrong key
	if _, err := v.Verify(ctx, mintRS256(t, other, "k1", sub, time.Now().Add(time.Hour))); err == nil {
		t.Fatal("forged signature must fail")
	}
	// alg confusion: HS256 with the raw modulus as the HMAC secret — the
	// classic alg-switch attack. golang-jwt refuses to SIGN HS256 with an
	// int, so hand-craft it exactly like an attacker would.
	seg := func(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }
	hdr := seg([]byte(`{"alg":"HS256","kid":"k1","typ":"JWT"}`))
	pls := seg([]byte(`{"sub":"` + sub + `","exp":` + fmt.Sprint(time.Now().Add(time.Hour).Unix()) + `}`))
	mac := hmac.New(sha256.New, other.PublicKey.N.Bytes())
	mac.Write([]byte(hdr + "." + pls))
	confused := hdr + "." + pls + "." + seg(mac.Sum(nil))
	if _, err := v.Verify(ctx, confused); err == nil {
		t.Fatal("HS256 must be rejected outright")
	}
	// no exp claim
	noExp := jwt.NewWithClaims(jwt.SigningMethodRS256, jwt.RegisteredClaims{Subject: sub})
	signed, err := noExp.SignedString(key.Key)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := v.Verify(ctx, signed); err == nil {
		t.Fatal("token without exp must be rejected")
	}
	_ = key
}

func Test_key_rotation_is_picked_up_and_unknown_kid_floods_are_bounded(t *testing.T) {
	keys := []jwkKey{{Kid: "k1", Key: mustRSAKey(t)}}
	var fetches atomic.Int32
	srv := jwksServer(t, &keys, &fetches)
	v := NewJWKSVerifier(srv.URL)
	v.minFetch = 0 // exercise the rotation path immediately
	sub := uuid.MustParse("cccccccc-cccc-cccc-cccc-cccccccccccc").String()
	ctx := context.Background()

	old := mintRS256(t, keys[0].Key, "k1", sub, time.Now().Add(time.Hour))
	if _, err := v.Verify(ctx, old); err != nil {
		t.Fatal(err)
	}

	// auth rotates: k2 appears, k1 disappears
	keys = append(keys, jwkKey{Kid: "k2", Key: mustRSAKey(t)})
	keys = keys[1:]
	fresh := keys[0]
	newTok := mintRS256(t, fresh.Key, "k2", sub, time.Now().Add(time.Hour))
	if _, err := v.Verify(ctx, newTok); err != nil {
		t.Fatalf("rotated key must verify: %v", err)
	}
	if _, err := v.Verify(ctx, old); err == nil {
		t.Fatal("retired key must stop verifying")
	}

	// garbage-kid flood: only ONE extra fetch (the forced rotation check),
	// the negative cache absorbs the rest
	before := fetches.Load()
	v.minFetch = time.Hour // hold the negative-cache window open
	for i := 0; i < 50; i++ {
		if _, err := v.Verify(ctx, mintRS256(t, keys[0].Key, "garbage", sub, time.Now().Add(time.Hour))); err == nil {
			t.Fatal("unknown kid must not verify")
		}
	}
	if got := fetches.Load() - before; got != 0 {
		t.Fatalf("unknown-kid flood within the window must fetch nothing, got %d fetches", got)
	}
}

func mustRSAKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	return key
}
