package main

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
)

const maxContentLength = 4000

// app carries the handler dependencies; routes() builds the HTTP surface
// so tests can spin it with fakes.
type app struct {
	store    Store
	verifier Verifier
	hub      *Hub
	logger   *slog.Logger
	// wsOrigins is the explicit Origin allowlist for the WebSocket upgrade.
	// Empty Origin (native clients, tests) is allowed; a mismatched browser
	// Origin is a cross-site WebSocket hijack attempt and gets 403.
	wsOrigins map[string]bool
	upgrader  websocket.Upgrader
}

func newApp(store Store, verifier Verifier, hub *Hub, logger *slog.Logger, wsOrigins []string) *app {
	allow := map[string]bool{}
	for _, o := range wsOrigins {
		allow[strings.TrimSuffix(o, "/")] = true
	}
	return &app{
		store:     store,
		verifier:  verifier,
		hub:       hub,
		logger:    logger,
		wsOrigins: allow,
		upgrader: websocket.Upgrader{
			ReadBufferSize:  1024,
			WriteBufferSize: 1024,
			// Explicit allowlist, not return-true: the socket rides the
			// om_access cookie, and an unchecked Origin is a cross-site
			// WebSocket hijack attempt.
			CheckOrigin: func(r *http.Request) bool {
				origin := r.Header.Get("Origin")
				if origin == "" {
					return true // non-browser client (tests, native)
				}
				return allow[origin]
			},
		},
	}
}

func (a *app) error(w http.ResponseWriter, status int, code, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]string{"code": code, "message": message})
}

// identity resolves the caller from Authorization/om_access — the same
// extraction rule the gateway edge uses (cookie fallback included).
func (a *app) identity(w http.ResponseWriter, r *http.Request) (uuid.UUID, bool) {
	token := extractToken(r)
	if token == "" {
		a.error(w, http.StatusUnauthorized, "unauthorized", "A valid access token is required")
		return uuid.Nil, false
	}
	userID, err := a.verifier.Verify(r.Context(), token)
	if err != nil {
		a.error(w, http.StatusUnauthorized, "unauthorized", "A valid access token is required")
		return uuid.Nil, false
	}
	return userID, true
}

func extractToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if strings.HasPrefix(h, "Bearer ") {
		return strings.TrimPrefix(h, "Bearer ")
	}
	if c, err := r.Cookie("om_access"); err == nil {
		return c.Value
	}
	return ""
}

func (a *app) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/live", a.healthLive)
	mux.HandleFunc("GET /health/ready", a.healthReady)
	mux.HandleFunc("POST /api/v1/messaging/conversations", a.createConversation)
	mux.HandleFunc("GET /api/v1/messaging/conversations", a.listConversations)
	mux.HandleFunc("GET /api/v1/messaging/conversations/unread-count", a.unreadCount)
	mux.HandleFunc("GET /api/v1/messaging/conversations/{id}/messages", a.listMessages)
	mux.HandleFunc("POST /api/v1/messaging/conversations/{id}/messages", a.sendMessage)
	mux.HandleFunc("POST /api/v1/messaging/conversations/{id}/read", a.markRead)
	mux.HandleFunc("DELETE /api/v1/messaging/messages/{id}", a.deleteMessage)
	mux.HandleFunc("GET /ws", a.ws)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		a.error(w, http.StatusNotFound, "not_found", "Unknown messaging route")
	})
	return mux
}

func (a *app) healthLive(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

// healthReady reuses the shared pool — the old probe dialed a fresh DB per
// scrape, which could hang a scrape for the full timeout.
func (a *app) healthReady(w http.ResponseWriter, r *http.Request) {
	if pg, ok := a.store.(*PostgresStore); ok {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		if err := pg.db.PingContext(ctx); err != nil {
			w.WriteHeader(http.StatusServiceUnavailable)
			json.NewEncoder(w).Encode(map[string]string{"status": "db unreachable"})
			return
		}
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "ready"})
}

func (a *app) createConversation(w http.ResponseWriter, r *http.Request) {
	userID, ok := a.identity(w, r)
	if !ok {
		return
	}
	var body struct {
		OtherUserID string  `json:"otherUserId"`
		ListingID   *string `json:"listingId"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<10)).Decode(&body); err != nil {
		a.error(w, http.StatusBadRequest, "invalid_request", "Body must be JSON")
		return
	}
	otherID, err := uuid.Parse(body.OtherUserID)
	if err != nil {
		a.error(w, http.StatusBadRequest, "invalid_request", "otherUserId must be a uuid")
		return
	}
	if otherID == userID {
		a.error(w, http.StatusBadRequest, "invalid_request", "Cannot open a conversation with yourself")
		return
	}
	var listingID *uuid.UUID
	if body.ListingID != nil && *body.ListingID != "" {
		lid, err := uuid.Parse(*body.ListingID)
		if err != nil {
			a.error(w, http.StatusBadRequest, "invalid_request", "listingId must be a uuid")
			return
		}
		listingID = &lid
	}

	conv, created, err := a.store.CreateOrGetConversation(r.Context(), userID, otherID, listingID)
	if err != nil {
		a.logger.Error("create conversation failed", "err", err)
		a.error(w, http.StatusInternalServerError, "internal", "Something went wrong")
		return
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(conv)
}

func (a *app) listConversations(w http.ResponseWriter, r *http.Request) {
	userID, ok := a.identity(w, r)
	if !ok {
		return
	}
	convs, err := a.store.ListConversations(r.Context(), userID)
	if err != nil {
		a.logger.Error("list conversations failed", "err", err)
		a.error(w, http.StatusInternalServerError, "internal", "Something went wrong")
		return
	}
	if convs == nil {
		convs = []ConversationSummary{}
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"conversations": convs})
}

func (a *app) unreadCount(w http.ResponseWriter, r *http.Request) {
	userID, ok := a.identity(w, r)
	if !ok {
		return
	}
	n, err := a.store.UnreadTotal(r.Context(), userID)
	if err != nil {
		a.logger.Error("unread count failed", "err", err)
		a.error(w, http.StatusInternalServerError, "internal", "Something went wrong")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]int64{"count": n})
}

func (a *app) listMessages(w http.ResponseWriter, r *http.Request) {
	userID, ok := a.identity(w, r)
	if !ok {
		return
	}
	convID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		a.error(w, http.StatusBadRequest, "invalid_request", "conversation id must be a uuid")
		return
	}
	limit := 50
	if l := r.URL.Query().Get("limit"); l != "" {
		if n, err := strconv.Atoi(l); err == nil && n >= 1 && n <= 100 {
			limit = n
		} else {
			a.error(w, http.StatusBadRequest, "invalid_request", "limit must be 1..100")
			return
		}
	}
	var before *uuid.UUID
	if b := r.URL.Query().Get("before"); b != "" {
		id, err := uuid.Parse(b)
		if err != nil {
			a.error(w, http.StatusBadRequest, "invalid_request", "before must be a uuid")
			return
		}
		before = &id
	}

	msgs, err := a.store.ListMessages(r.Context(), userID, convID, before, limit)
	if errors.Is(err, ErrNotFound) {
		a.error(w, http.StatusNotFound, "not_found", "Unknown conversation")
		return
	}
	if err != nil {
		a.logger.Error("list messages failed", "err", err)
		a.error(w, http.StatusInternalServerError, "internal", "Something went wrong")
		return
	}
	if msgs == nil {
		msgs = []Message{}
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"messages": msgs})
}

func (a *app) sendMessage(w http.ResponseWriter, r *http.Request) {
	userID, ok := a.identity(w, r)
	if !ok {
		return
	}
	convID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		a.error(w, http.StatusBadRequest, "invalid_request", "conversation id must be a uuid")
		return
	}
	var body struct {
		Content string `json:"content"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxContentLength+512)).Decode(&body); err != nil {
		a.error(w, http.StatusBadRequest, "invalid_request", "Body must be JSON")
		return
	}
	content := strings.TrimSpace(body.Content)
	if content == "" {
		a.error(w, http.StatusBadRequest, "invalid_request", "Message must not be empty")
		return
	}
	if len(content) > maxContentLength {
		a.error(w, http.StatusBadRequest, "invalid_request", "Message too long (max 4000)")
		return
	}

	msg, err := a.store.CreateMessage(r.Context(), userID, convID, content)
	if errors.Is(err, ErrNotFound) {
		a.error(w, http.StatusNotFound, "not_found", "Unknown conversation")
		return
	}
	if err != nil {
		a.logger.Error("send message failed", "err", err)
		a.error(w, http.StatusInternalServerError, "internal", "Something went wrong")
		return
	}

	// Push to every live participant socket (sender included — their other
	// tabs stay in sync). Persistence already committed: the socket is a
	// fan-out convenience, never the source of truth.
	if participants, err := a.store.ParticipantIDs(r.Context(), convID); err == nil {
		a.hub.Broadcast(a.logger, participants, wsEvent{Type: "message.created", Message: &msg})
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(msg)
}

func (a *app) markRead(w http.ResponseWriter, r *http.Request) {
	userID, ok := a.identity(w, r)
	if !ok {
		return
	}
	convID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		a.error(w, http.StatusBadRequest, "invalid_request", "conversation id must be a uuid")
		return
	}
	if err := a.store.MarkRead(r.Context(), userID, convID); errors.Is(err, ErrNotFound) {
		a.error(w, http.StatusNotFound, "not_found", "Unknown conversation")
		return
	} else if err != nil {
		a.logger.Error("mark read failed", "err", err)
		a.error(w, http.StatusInternalServerError, "internal", "Something went wrong")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *app) deleteMessage(w http.ResponseWriter, r *http.Request) {
	userID, ok := a.identity(w, r)
	if !ok {
		return
	}
	msgID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		a.error(w, http.StatusBadRequest, "invalid_request", "message id must be a uuid")
		return
	}
	err = a.store.SoftDeleteMessage(r.Context(), userID, msgID)
	switch {
	case errors.Is(err, ErrNotFound):
		a.error(w, http.StatusNotFound, "not_found", "Unknown message")
	case errors.Is(err, ErrForbidden):
		a.error(w, http.StatusForbidden, "forbidden", "You can only delete your own messages")
	case err != nil:
		a.logger.Error("delete message failed", "err", err)
		a.error(w, http.StatusInternalServerError, "internal", "Something went wrong")
	default:
		w.WriteHeader(http.StatusNoContent)
	}
}

// ws upgrades an authenticated connection to the server→client push channel.
// The client never sends application frames — REST is the write path — so
// the read pump only babysits liveness (pong/close) and enforces a tiny
// read limit on principle.
func (a *app) ws(w http.ResponseWriter, r *http.Request) {
	userID, ok := a.identity(w, r)
	if !ok {
		return
	}
	conn, err := a.upgrader.Upgrade(w, r, nil)
	if err != nil {
		// Upgrade already wrote the HTTP error (e.g. 403 on bad Origin)
		return
	}
	c := &wsConn{raw: conn, owner: userID}
	if !a.hub.add(userID, c) {
		// reconnect storm / too many tabs: refuse the socket politely
		conn.WriteControl(websocket.CloseMessage,
			websocket.FormatCloseMessage(websocket.CloseTryAgainLater, "too many connections"),
			time.Now().Add(time.Second))
		conn.Close()
		return
	}
	defer a.hub.remove(userID, c)

	conn.SetReadLimit(512)
	conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	conn.SetPongHandler(func(string) error {
		conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		return nil
	})
	for {
		if _, _, err := conn.ReadMessage(); err != nil {
			return
		}
		conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	}
}
