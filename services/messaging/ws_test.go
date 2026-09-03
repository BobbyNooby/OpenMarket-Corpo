package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
)

// The socket is fan-out only: messages enter via REST and are pushed to
// every live participant socket (including the sender's other tabs).

func dialWS(t *testing.T, srv *httptest.Server, token string, origin string) (*websocket.Conn, *http.Response, error) {
	t.Helper()
	hdr := http.Header{}
	if token != "" {
		hdr.Set("Authorization", "Bearer "+token)
	}
	if origin != "" {
		hdr.Set("Origin", origin)
	}
	u := "ws" + strings.TrimPrefix(srv.URL, "http") + "/ws"
	return websocket.DefaultDialer.Dial(u, hdr)
}

func readEvent(t *testing.T, c *websocket.Conn) wsEvent {
	t.Helper()
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var ev wsEvent
	if err := c.ReadJSON(&ev); err != nil {
		t.Fatalf("read push: %v", err)
	}
	return ev
}

func Test_message_post_pushes_to_every_participant_socket(t *testing.T) {
	store := &fakeStore{
		participants: []uuid.UUID{userA, userB},
		createdMsg:   Message{ID: uuid.New(), ConversationID: conv1, SenderID: userB, Content: "trade?"},
	}
	a := newTestApp(store)
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	connA, _, err := dialWS(t, srv, "tok-A", "")
	if err != nil {
		t.Fatalf("dial A: %v", err)
	}
	defer connA.Close()
	connB, _, err := dialWS(t, srv, "tok-B", "")
	if err != nil {
		t.Fatalf("dial B: %v", err)
	}
	defer connB.Close()
	time.Sleep(50 * time.Millisecond) // let the hub register both sockets

	rec := do(t, a, http.MethodPost,
		"/api/v1/messaging/conversations/"+conv1.String()+"/messages", "tok-B",
		`{"content":"trade?"}`)
	if rec.Code != http.StatusCreated {
		t.Fatalf("send must be 201, got %d", rec.Code)
	}

	evA := readEvent(t, connA)
	evB := readEvent(t, connB)
	for name, ev := range map[string]wsEvent{"A": evA, "B": evB} {
		if ev.Type != "message.created" {
			t.Fatalf("%s: want message.created, got %q", name, ev.Type)
		}
		if ev.Message == nil || ev.Message.Content != "trade?" {
			t.Fatalf("%s: push must carry the message, got %+v", name, ev.Message)
		}
		if ev.Message.SenderID != userB {
			t.Fatalf("%s: wrong sender %v", name, ev.Message.SenderID)
		}
	}
}

func Test_non_participant_gets_no_push(t *testing.T) {
	// C is connected but not in the conversation.
	store := &fakeStore{
		participants: []uuid.UUID{userA},
		createdMsg:   Message{ID: uuid.New(), ConversationID: conv1, SenderID: userA, Content: "hi"},
	}
	a := newTestApp(store)
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	connC, _, err := dialWS(t, srv, "tok-C", "")
	if err != nil {
		t.Fatal(err)
	}
	defer connC.Close()
	time.Sleep(50 * time.Millisecond)

	if rec := do(t, a, http.MethodPost,
		"/api/v1/messaging/conversations/"+conv1.String()+"/messages", "tok-A",
		`{"content":"hi"}`); rec.Code != http.StatusCreated {
		t.Fatalf("send: %d", rec.Code)
	}

	connC.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
	if _, _, err := connC.ReadMessage(); err == nil {
		t.Fatal("non-participant socket must not receive the push")
	}
}

func Test_ws_requires_a_valid_token(t *testing.T) {
	a := newTestApp(&fakeStore{})
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	if _, resp, err := dialWS(t, srv, "", ""); err == nil {
		t.Fatal("unauthenticated upgrade must fail")
	} else if resp != nil && resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("want 401 on upgrade, got %d", resp.StatusCode)
	}
	if _, resp, err := dialWS(t, srv, "tok-nope", ""); err == nil {
		t.Fatal("invalid token upgrade must fail")
	} else if resp != nil && resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("want 401 on upgrade, got %d", resp.StatusCode)
	}
}

func Test_ws_rejects_disallowed_origins(t *testing.T) {
	a := newTestApp(&fakeStore{})
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	if _, resp, err := dialWS(t, srv, "tok-A", "https://evil.example"); err == nil {
		t.Fatal("cross-origin upgrade must be refused")
	} else if resp != nil && resp.StatusCode != http.StatusForbidden {
		t.Fatalf("want 403 on bad origin, got %d", resp.StatusCode)
	}
	if _, _, err := dialWS(t, srv, "tok-A", "http://localhost:5173"); err != nil {
		t.Fatalf("allowed origin must upgrade: %v", err)
	}
}

// The push envelope shape is a client contract — pin the JSON keys.
func Test_push_envelope_serializes_snake_contract_keys(t *testing.T) {
	msg := Message{ID: uuid.New(), ConversationID: conv1, SenderID: userA, Content: "x"}
	ev := wsEvent{Type: "message.created", Message: &msg}
	b, err := json.Marshal(ev)
	if err != nil {
		t.Fatal(err)
	}
	var raw map[string]any
	if err := json.Unmarshal(b, &raw); err != nil {
		t.Fatal(err)
	}
	if raw["type"] != "message.created" {
		t.Fatal("type key missing")
	}
	m, ok := raw["message"].(map[string]any)
	if !ok {
		t.Fatal("message key missing")
	}
	for _, key := range []string{"id", "conversationId", "senderId", "content", "createdAt", "isDeleted"} {
		if _, ok := m[key]; !ok {
			t.Fatalf("message envelope must carry %q", key)
		}
	}
}
