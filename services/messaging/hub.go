package main

import (
	"log/slog"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
)

// wsConn is one live browser socket. Writes are serialized: the read pump
// owns control frames while pushes arrive from any broadcast goroutine.
type wsConn struct {
	raw *websocket.Conn
	mu  sync.Mutex
}

func (c *wsConn) write(payload any) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.raw.SetWriteDeadline(time.Now().Add(5 * time.Second))
	return c.raw.WriteJSON(payload)
}

// Hub tracks live sockets per user and pushes events to them. A user may
// hold several sockets (tabs); every socket gets every push.
type Hub struct {
	mu    sync.RWMutex
	conns map[uuid.UUID]map[*wsConn]struct{}
}

func NewHub() *Hub {
	return &Hub{conns: map[uuid.UUID]map[*wsConn]struct{}{}}
}

func (h *Hub) add(userID uuid.UUID, c *wsConn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.conns[userID] == nil {
		h.conns[userID] = map[*wsConn]struct{}{}
	}
	h.conns[userID][c] = struct{}{}
}

func (h *Hub) remove(userID uuid.UUID, c *wsConn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if set := h.conns[userID]; set != nil {
		delete(set, c)
		if len(set) == 0 {
			delete(h.conns, userID)
		}
	}
}

// wsEvent is the push envelope (see contracts/openapi/messaging.v1.yaml).
type wsEvent struct {
	Type    string   `json:"type"`
	Message *Message `json:"message,omitempty"`
}

// Broadcast delivers the event to every live socket of every listed user.
// Dead sockets are dropped silently — the client reconnects; delivery is
// best-effort by design (REST remains the source of truth).
func (h *Hub) Broadcast(logger *slog.Logger, userIDs []uuid.UUID, ev wsEvent) {
	h.mu.RLock()
	var targets []*wsConn
	for _, uid := range userIDs {
		for c := range h.conns[uid] {
			targets = append(targets, c)
		}
	}
	h.mu.RUnlock()

	for _, c := range targets {
		if err := c.write(ev); err != nil {
			logger.Warn("ws push failed — dropping socket", "err", err)
			c.raw.Close()
		}
	}
}
