package main

import (
	"log/slog"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
)

// maxConnsPerUser bounds socket accumulation (tabs, reconnect storms) —
// the 9th concurrent socket displaces the oldest instead of growing the
// fan-out unbounded (audit Important fix).
const maxConnsPerUser = 8

// wsConn is one live browser socket. Writes are serialized: the read pump
// owns control frames while pushes arrive from any broadcast goroutine.
type wsConn struct {
	raw   *websocket.Conn
	mu    sync.Mutex
	owner uuid.UUID
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

// add registers a socket; if the user is at the cap, the NEW connection is
// rejected (nil return) so a reconnect storm can't grow the fan-out.
func (h *Hub) add(userID uuid.UUID, c *wsConn) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if len(h.conns[userID]) >= maxConnsPerUser {
		return false
	}
	if h.conns[userID] == nil {
		h.conns[userID] = map[*wsConn]struct{}{}
	}
	h.conns[userID][c] = struct{}{}
	return true
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
// Writes run on their own goroutines: a stalled participant socket (5s
// write deadline) must never stall the sender's REST response. Dead
// sockets are dropped — the client reconnects; REST remains the source of
// truth.
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
		go func(c *wsConn) {
			if err := c.write(ev); err != nil {
				logger.Warn("ws push failed — dropping socket", "err", err)
				c.raw.Close()
			}
		}(c)
	}
}
