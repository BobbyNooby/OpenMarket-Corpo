package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/google/uuid"
)

// ── fakes ──────────────────────────────────────────────────────────────

type fakeVerifier struct {
	users map[string]uuid.UUID // raw token → user id
	calls int
}

func (f *fakeVerifier) Verify(_ context.Context, raw string) (uuid.UUID, error) {
	f.calls++
	id, ok := f.users[raw]
	if !ok {
		return uuid.Nil, errors.New("unknown token")
	}
	return id, nil
}

type fakeStore struct {
	createResult   Conversation
	createCreated  bool
	createErr      error
	listResult     []ConversationSummary
	unread         int64
	messages       []Message
	messagesErr    error
	capturedLimit  int
	capturedBefore *uuid.UUID
	createdMsg     Message
	createMsgErr   error
	capturedBody   string
	markReadErr    error
	deleteErr      error
	participants   []uuid.UUID
}

func (f *fakeStore) CreateOrGetConversation(_ context.Context, _, _ uuid.UUID, _ *uuid.UUID) (Conversation, bool, error) {
	return f.createResult, f.createCreated, f.createErr
}
func (f *fakeStore) ListConversations(_ context.Context, _ uuid.UUID) ([]ConversationSummary, error) {
	return f.listResult, nil
}
func (f *fakeStore) UnreadTotal(_ context.Context, _ uuid.UUID) (int64, error) { return f.unread, nil }
func (f *fakeStore) ListMessages(_ context.Context, _ uuid.UUID, _ uuid.UUID, before *uuid.UUID, limit int) ([]Message, error) {
	f.capturedLimit = limit
	f.capturedBefore = before
	return f.messages, f.messagesErr
}
func (f *fakeStore) CreateMessage(_ context.Context, _ uuid.UUID, _ uuid.UUID, content string) (Message, error) {
	f.capturedBody = content
	return f.createdMsg, f.createMsgErr
}
func (f *fakeStore) MarkRead(_ context.Context, _ uuid.UUID, _ uuid.UUID) error {
	return f.markReadErr
}
func (f *fakeStore) SoftDeleteMessage(_ context.Context, _ uuid.UUID, _ uuid.UUID) error {
	return f.deleteErr
}
func (f *fakeStore) ParticipantIDs(_ context.Context, _ uuid.UUID) ([]uuid.UUID, error) {
	return f.participants, nil
}

// ── harness ────────────────────────────────────────────────────────────

var (
	userA = uuid.MustParse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
	userB = uuid.MustParse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
	userC = uuid.MustParse("cccccccc-cccc-cccc-cccc-cccccccccccc")
	conv1 = uuid.MustParse("11111111-1111-1111-1111-111111111111")
)

func newTestApp(store *fakeStore) *app {
	verifier := &fakeVerifier{users: map[string]uuid.UUID{
		"tok-A": userA, "tok-B": userB, "tok-C": userC,
	}}
	return newApp(store, verifier, NewHub(), slog.New(slog.NewTextHandler(&bytes.Buffer{}, nil)),
		[]string{"http://localhost:5173"})
}

func do(t *testing.T, a *app, method, path, token, body string) *httptest.ResponseRecorder {
	t.Helper()
	var req *http.Request
	if body != "" {
		req = httptest.NewRequest(method, path, strings.NewReader(body))
	} else {
		req = httptest.NewRequest(method, path, nil)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	a.routes().ServeHTTP(rec, req)
	return rec
}

// ── auth contract ──────────────────────────────────────────────────────

func Test_missing_token_is_401_without_consulting_the_verifier(t *testing.T) {
	a := newTestApp(&fakeStore{})
	rec := do(t, a, http.MethodGet, "/api/v1/messaging/conversations", "", "")
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("want 401, got %d", rec.Code)
	}
	if a.verifier.(*fakeVerifier).calls != 0 {
		t.Fatal("no token must not reach the verifier")
	}
}

func Test_unknown_token_is_401(t *testing.T) {
	a := newTestApp(&fakeStore{})
	rec := do(t, a, http.MethodGet, "/api/v1/messaging/conversations", "tok-nope", "")
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("want 401, got %d", rec.Code)
	}
}

// ── conversation create ────────────────────────────────────────────────

func Test_creating_a_conversation_is_201_and_a_replay_is_200(t *testing.T) {
	store := &fakeStore{createCreated: true}
	a := newTestApp(store)
	rec := do(t, a, http.MethodPost, "/api/v1/messaging/conversations", "tok-A",
		`{"otherUserId":"`+userB.String()+`"}`)
	if rec.Code != http.StatusCreated {
		t.Fatalf("first create must be 201, got %d", rec.Code)
	}

	store.createCreated = false
	rec = do(t, a, http.MethodPost, "/api/v1/messaging/conversations", "tok-A",
		`{"otherUserId":"`+userB.String()+`"}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("replay must be 200, got %d", rec.Code)
	}
}

func Test_self_conversation_is_400(t *testing.T) {
	a := newTestApp(&fakeStore{})
	rec := do(t, a, http.MethodPost, "/api/v1/messaging/conversations", "tok-A",
		`{"otherUserId":"`+userA.String()+`"}`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("want 400, got %d", rec.Code)
	}
}

func Test_bad_uuid_body_is_400(t *testing.T) {
	a := newTestApp(&fakeStore{})
	rec := do(t, a, http.MethodPost, "/api/v1/messaging/conversations", "tok-A",
		`{"otherUserId":"not-a-uuid"}`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("want 400, got %d", rec.Code)
	}
}

// ── messages ───────────────────────────────────────────────────────────

func Test_sending_into_an_unknown_or_foreign_conversation_is_404(t *testing.T) {
	store := &fakeStore{createMsgErr: ErrNotFound}
	a := newTestApp(store)
	rec := do(t, a, http.MethodPost, "/api/v1/messaging/conversations/"+conv1.String()+"/messages", "tok-A",
		`{"content":"hello"}`)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("non-participant must be indistinguishable from unknown (404), got %d", rec.Code)
	}
}

func Test_message_content_bounds(t *testing.T) {
	store := &fakeStore{createdMsg: Message{ID: uuid.New(), ConversationID: conv1, SenderID: userA}}
	a := newTestApp(store)

	if rec := do(t, a, http.MethodPost,
		"/api/v1/messaging/conversations/"+conv1.String()+"/messages", "tok-A",
		`{"content":"   "}`); rec.Code != http.StatusBadRequest {
		t.Fatalf("blank content must be 400, got %d", rec.Code)
	}
	long := strings.Repeat("x", maxContentLength+1)
	if rec := do(t, a, http.MethodPost,
		"/api/v1/messaging/conversations/"+conv1.String()+"/messages", "tok-A",
		`{"content":"`+long+`"}`); rec.Code != http.StatusBadRequest {
		t.Fatalf("oversized content must be 400, got %d", rec.Code)
	}
	if rec := do(t, a, http.MethodPost,
		"/api/v1/messaging/conversations/"+conv1.String()+"/messages", "tok-A",
		`{"content":"  hi  "}`); rec.Code != http.StatusCreated {
		t.Fatalf("normal content must be 201, got %d", rec.Code)
	}
	if store.capturedBody != "hi" {
		t.Fatalf("content must be trimmed before storage, got %q", store.capturedBody)
	}
}

func Test_message_pagination_limits_are_enforced(t *testing.T) {
	store := &fakeStore{}
	a := newTestApp(store)
	path := "/api/v1/messaging/conversations/" + conv1.String() + "/messages"

	if rec := do(t, a, http.MethodGet, path+"?limit=0", "tok-A", ""); rec.Code != http.StatusBadRequest {
		t.Fatal("limit=0 must be 400")
	}
	if rec := do(t, a, http.MethodGet, path+"?limit=101", "tok-A", ""); rec.Code != http.StatusBadRequest {
		t.Fatal("limit=101 must be 400")
	}
	if rec := do(t, a, http.MethodGet, path, "tok-A", ""); rec.Code != http.StatusOK {
		t.Fatal("no limit must be 200")
	}
	if store.capturedLimit != 50 {
		t.Fatalf("default limit must be 50, got %d", store.capturedLimit)
	}
	before := uuid.New()
	do(t, a, http.MethodGet, path+"?limit=10&before="+before.String(), "tok-A", "")
	if store.capturedBefore == nil || *store.capturedBefore != before {
		t.Fatal("before cursor must reach the store")
	}
}

func Test_list_messages_masks_non_participants_as_404(t *testing.T) {
	store := &fakeStore{messagesErr: ErrNotFound}
	a := newTestApp(store)
	rec := do(t, a, http.MethodGet, "/api/v1/messaging/conversations/"+conv1.String()+"/messages", "tok-A", "")
	if rec.Code != http.StatusNotFound {
		t.Fatalf("want masked 404, got %d", rec.Code)
	}
}

// ── read + delete ──────────────────────────────────────────────────────

func Test_mark_read_answers_204_and_masks_unknown_as_404(t *testing.T) {
	a := newTestApp(&fakeStore{})
	if rec := do(t, a, http.MethodPost, "/api/v1/messaging/conversations/"+conv1.String()+"/read", "tok-A", ""); rec.Code != http.StatusNoContent {
		t.Fatalf("want 204, got %d", rec.Code)
	}
	a2 := newTestApp(&fakeStore{markReadErr: ErrNotFound})
	if rec := do(t, a2, http.MethodPost, "/api/v1/messaging/conversations/"+conv1.String()+"/read", "tok-A", ""); rec.Code != http.StatusNotFound {
		t.Fatalf("want 404, got %d", rec.Code)
	}
}

func Test_delete_message_distinguishes_forbidden_from_missing(t *testing.T) {
	a := newTestApp(&fakeStore{deleteErr: ErrForbidden})
	if rec := do(t, a, http.MethodDelete, "/api/v1/messaging/messages/"+uuid.New().String(), "tok-A", ""); rec.Code != http.StatusForbidden {
		t.Fatalf("not-the-sender must be 403, got %d", rec.Code)
	}
	a2 := newTestApp(&fakeStore{deleteErr: ErrNotFound})
	if rec := do(t, a2, http.MethodDelete, "/api/v1/messaging/messages/"+uuid.New().String(), "tok-A", ""); rec.Code != http.StatusNotFound {
		t.Fatalf("unknown message must be 404, got %d", rec.Code)
	}
}

// ── unread count ───────────────────────────────────────────────────────

func Test_unread_count_returns_the_store_total(t *testing.T) {
	a := newTestApp(&fakeStore{unread: 7})
	rec := do(t, a, http.MethodGet, "/api/v1/messaging/conversations/unread-count", "tok-A", "")
	var body struct {
		Count int64 `json:"count"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Count != 7 {
		t.Fatalf("want count 7, got %d", body.Count)
	}
}
