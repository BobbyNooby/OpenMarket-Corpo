package blocklist

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"testing"
	"time"
)

type fakeStore struct {
	setCall   map[string]setCall
	deleted   []string
	existing  map[string]bool
	setErr    error
	existsErr error
}

type setCall struct {
	val string
	ttl time.Duration
}

func newFakeStore() *fakeStore {
	return &fakeStore{setCall: map[string]setCall{}, existing: map[string]bool{}}
}

func (f *fakeStore) Set(_ context.Context, key, val string, ttl time.Duration) error {
	if f.setErr != nil {
		return f.setErr
	}
	f.setCall[key] = setCall{val, ttl}
	return nil
}

func (f *fakeStore) Delete(_ context.Context, key string) error {
	f.deleted = append(f.deleted, key)
	delete(f.existing, key)
	return nil
}

func (f *fakeStore) Exists(_ context.Context, key string) (bool, error) {
	if f.existsErr != nil {
		return false, f.existsErr
	}
	return f.existing[key], nil
}

func Test_banned_user_is_blocklisted_until_unbanned(t *testing.T) {
	store := newFakeStore()
	bl := New(store, slog.Default())

	if err := bl.Apply(context.Background(), "user.banned",
		[]byte(`{"userId":"u-1","reason":"spam","bannedBy":"a-1"}`)); err != nil {
		t.Fatal(err)
	}
	store.existing[keyPrefix+"u-1"] = true // as the store would after Set

	if !bl.Blocked(context.Background(), "u-1") {
		t.Fatal("banned user must be blocked")
	}
	if bl.Blocked(context.Background(), "u-2") {
		t.Fatal("unknown user must not be blocked")
	}

	if err := bl.Apply(context.Background(), "user.unbanned", []byte(`{"userId":"u-1"}`)); err != nil {
		t.Fatal(err)
	}
	if store.existing[keyPrefix+"u-1"] {
		t.Fatal("unban must lift the block")
	}
}

func Test_permanent_ban_sets_no_ttl_temporary_ban_sets_expiry(t *testing.T) {
	store := newFakeStore()
	bl := New(store, slog.Default())
	future := time.Now().Add(time.Hour).UTC().Format(time.RFC3339)

	_ = bl.Apply(context.Background(), "user.banned", []byte(`{"userId":"u-perm"}`))
	_ = bl.Apply(context.Background(), "user.banned",
		[]byte(`{"userId":"u-temp","expiresAt":"`+future+`"}`))

	if ttl := store.setCall[keyPrefix+"u-perm"].ttl; ttl != 0 {
		t.Fatalf("permanent ban must have no ttl, got %v", ttl)
	}
	if ttl := store.setCall[keyPrefix+"u-temp"].ttl; ttl <= 0 || ttl > time.Hour {
		t.Fatalf("temp ban ttl must be ~1h, got %v", ttl)
	}
}

func Test_already_expired_ban_is_not_blocklisted(t *testing.T) {
	store := newFakeStore()
	bl := New(store, slog.Default())
	past := time.Now().Add(-time.Minute).UTC().Format(time.RFC3339)

	err := bl.Apply(context.Background(), "user.banned",
		[]byte(`{"userId":"u-old","expiresAt":"`+past+`"}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(store.setCall) != 0 {
		t.Fatal("an expired ban must not be blocklisted — introspection won't enforce it either")
	}
}

func Test_malformed_events_are_rejected_not_applied(t *testing.T) {
	store := newFakeStore()
	bl := New(store, slog.Default())

	if err := bl.Apply(context.Background(), "user.banned", []byte(`{not json`)); err == nil {
		t.Fatal("bad json must error")
	}
	if err := bl.Apply(context.Background(), "user.banned", []byte(`{"reason":"no userId"}`)); err == nil {
		t.Fatal("payload without userId must error")
	}
	if err := bl.Apply(context.Background(), "user.banned",
		[]byte(`{"userId":"u","expiresAt":"tomorrow"}`)); err == nil {
		t.Fatal("bad expiresAt must error")
	}
	if len(store.setCall) != 0 {
		t.Fatal("nothing must be applied for malformed payloads")
	}
}

func Test_store_failure_fails_open(t *testing.T) {
	store := newFakeStore()
	store.existsErr = errors.New("redis down")
	bl := New(store, slog.Default())

	if bl.Blocked(context.Background(), "u-1") {
		t.Fatal("store outage must fail open (introspection is the authority)")
	}
}

func Test_user_deleted_lifts_the_block(t *testing.T) {
	store := newFakeStore()
	bl := New(store, slog.Default())
	store.existing[keyPrefix+"u-1"] = true

	if err := bl.Apply(context.Background(), "user.deleted",
		[]byte(`{"userId":"u-1","erased":true}`)); err != nil {
		t.Fatal(err)
	}
	if len(store.deleted) != 1 || store.deleted[0] != keyPrefix+"u-1" {
		t.Fatal("deletion must lift the block (introspection already kills the tokens)")
	}
}

func Test_roles_changed_is_ignored(t *testing.T) {
	store := newFakeStore()
	bl := New(store, slog.Default())

	if err := bl.Apply(context.Background(), "user.roles_changed",
		[]byte(`{"userId":"u-1","newRoles":["admin"]}`)); err != nil {
		t.Fatal(err)
	}
	if len(store.setCall) != 0 || len(store.deleted) != 0 {
		t.Fatal("role changes carry no blocklist meaning")
	}
}

func Test_sub_from_token_reads_the_unverified_claim(t *testing.T) {
	payload, _ := json.Marshal(map[string]string{"sub": "u-42"})
	tok := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"RS256"}`)) + "." +
		base64.RawURLEncoding.EncodeToString(payload) + ".sig"

	if got := SubFromToken(tok); got != "u-42" {
		t.Fatalf("sub = %q, want u-42", got)
	}
	if got := SubFromToken("not-a-jwt"); got != "" {
		t.Fatalf("garbage token must yield empty sub, got %q", got)
	}
}
