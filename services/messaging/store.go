package main

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"github.com/google/uuid"
)

// Sentinel errors the handlers translate into HTTP: not_found is masked
// (unknown conversation and non-participant are indistinguishable — v1
// parity), forbidden only leaks for the sender-scoped delete path where
// the message's existence is already known.
var (
	ErrNotFound      = errors.New("not_found")
	ErrForbidden     = errors.New("forbidden")
	ErrAlreadyExists = errors.New("already_exists")
)

type Conversation struct {
	ID        uuid.UUID  `json:"id"`
	CreatedAt time.Time  `json:"createdAt"`
	UpdatedAt time.Time  `json:"updatedAt"`
	ListingID *uuid.UUID `json:"listingId,omitempty"` // catalogue-owned listing; a dangling id is fine
}

type Message struct {
	ID             uuid.UUID  `json:"id"`
	ConversationID uuid.UUID  `json:"conversationId"`
	SenderID       uuid.UUID  `json:"senderId"`
	Content        string     `json:"content"`
	CreatedAt      time.Time  `json:"createdAt"`
	EditedAt       *time.Time `json:"editedAt,omitempty"`
	IsDeleted      bool       `json:"isDeleted"`
}

type ConversationSummary struct {
	Conversation
	OtherUserID uuid.UUID `json:"otherUserId"`
	LastMessage *Message  `json:"lastMessage,omitempty"`
	Unread      int64     `json:"unread"`
}

// Store is the persistence surface the handlers need — unit tests use a
// fake, production PostgresStore.
type Store interface {
	// CreateOrGetConversation returns the 1:1 conversation for the pair
	// (+ optional listing), creating it if absent. created=false marks an
	// idempotent replay of a prior create.
	CreateOrGetConversation(ctx context.Context, userID, otherUserID uuid.UUID, listingID *uuid.UUID) (Conversation, bool, error)
	ListConversations(ctx context.Context, userID uuid.UUID) ([]ConversationSummary, error)
	UnreadTotal(ctx context.Context, userID uuid.UUID) (int64, error)
	ListMessages(ctx context.Context, userID, conversationID uuid.UUID, before *uuid.UUID, limit int) ([]Message, error)
	// CreateMessage inserts, bumps the conversation's updated_at and marks
	// the sender's own read pointer — sending IS reading your own message.
	CreateMessage(ctx context.Context, userID, conversationID uuid.UUID, content string) (Message, error)
	MarkRead(ctx context.Context, userID, conversationID uuid.UUID) error
	// SoftDeleteMessage is sender-only: ErrNotFound for unknown ids,
	// ErrForbidden when the caller isn't the sender.
	SoftDeleteMessage(ctx context.Context, userID, messageID uuid.UUID) error
	ParticipantIDs(ctx context.Context, conversationID uuid.UUID) ([]uuid.UUID, error)
}

type PostgresStore struct {
	db *sql.DB
}

func NewPostgresStore(db *sql.DB) *PostgresStore { return &PostgresStore{db: db} }

func (s *PostgresStore) CreateOrGetConversation(ctx context.Context, userID, otherUserID uuid.UUID, listingID *uuid.UUID) (Conversation, bool, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return Conversation{}, false, err
	}
	defer tx.Rollback()

	// Serialize same-pair creation: the uniqueness that makes this
	// idempotent is the pair itself, and a check-then-insert race would
	// fork the thread into two (audit fix — the (conversation_id, user_id)
	// PK only guards membership, not pair count). A hash collision merely
	// serializes an unrelated pair for a moment; correctness is unaffected.
	var lock int
	if err := tx.QueryRowContext(ctx, `SELECT pg_advisory_xact_lock(hashtext(least($1::text, $2::text) || greatest($1::text, $2::text)))`,
		userID, otherUserID).Scan(&lock); err != nil {
		return Conversation{}, false, err
	}

	var existing Conversation
	err = tx.QueryRowContext(ctx, `
		SELECT c.id, c.created_at, c.updated_at, c.listing_id
		FROM conversations c
		JOIN conversation_participants p1 ON p1.conversation_id = c.id AND p1.user_id = $1
		JOIN conversation_participants p2 ON p2.conversation_id = c.id AND p2.user_id = $2
		WHERE (c.listing_id = $3 OR (c.listing_id IS NULL AND $3 IS NULL))
		LIMIT 1`, userID, otherUserID, listingID).
		Scan(&existing.ID, &existing.CreatedAt, &existing.UpdatedAt, &existing.ListingID)
	if err == nil {
		return existing, false, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return Conversation{}, false, err
	}

	var conv Conversation
	err = tx.QueryRowContext(ctx, `
		INSERT INTO conversations (listing_id) VALUES ($1)
		RETURNING id, created_at, updated_at, listing_id`, listingID).
		Scan(&conv.ID, &conv.CreatedAt, &conv.UpdatedAt, &conv.ListingID)
	if err != nil {
		return Conversation{}, false, err
	}
	for _, uid := range []uuid.UUID{userID, otherUserID} {
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO conversation_participants (conversation_id, user_id) VALUES ($1, $2)`,
			conv.ID, uid); err != nil {
			return Conversation{}, false, err
		}
	}
	if err := tx.Commit(); err != nil {
		return Conversation{}, false, err
	}
	return conv, true, nil
}

func (s *PostgresStore) ListConversations(ctx context.Context, userID uuid.UUID) ([]ConversationSummary, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT c.id, c.created_at, c.updated_at, c.listing_id,
		       other.user_id,
		       lm.id, lm.conversation_id, lm.sender_id, lm.content, lm.created_at, lm.edited_at, lm.is_deleted,
		       COALESCE((
		           SELECT count(*) FROM messages m
		           WHERE m.conversation_id = c.id
		             AND m.is_deleted = false
		             AND m.sender_id <> $1
		             AND (p.last_read_at IS NULL OR m.created_at > p.last_read_at)
		       ), 0) AS unread
		FROM conversations c
		JOIN conversation_participants p ON p.conversation_id = c.id AND p.user_id = $1
		JOIN conversation_participants other ON other.conversation_id = c.id AND other.user_id <> $1
		LEFT JOIN LATERAL (
		    SELECT * FROM messages m WHERE m.conversation_id = c.id
		    ORDER BY m.created_at DESC LIMIT 1
		) lm ON true
		ORDER BY c.updated_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []ConversationSummary
	for rows.Next() {
		var cs ConversationSummary
		var lastID, lastConvID, lastSender sql.NullString
		var lastContent sql.NullString
		var lastCreatedAt sql.NullTime
		var lastEditedAt sql.NullTime
		var lastDeleted sql.NullBool
		if err := rows.Scan(&cs.ID, &cs.CreatedAt, &cs.UpdatedAt, &cs.ListingID,
			&cs.OtherUserID,
			&lastID, &lastConvID, &lastSender, &lastContent, &lastCreatedAt, &lastEditedAt, &lastDeleted,
			&cs.Unread); err != nil {
			return nil, err
		}
		if lastID.Valid {
			msgID, _ := uuid.Parse(lastID.String)
			convID, _ := uuid.Parse(lastConvID.String)
			senderID, _ := uuid.Parse(lastSender.String)
			cs.LastMessage = &Message{
				ID: msgID, ConversationID: convID, SenderID: senderID,
				Content: lastContent.String, CreatedAt: lastCreatedAt.Time,
				EditedAt: nullableTime(lastEditedAt), IsDeleted: lastDeleted.Bool,
			}
		}
		out = append(out, cs)
	}
	return out, rows.Err()
}

func (s *PostgresStore) UnreadTotal(ctx context.Context, userID uuid.UUID) (int64, error) {
	var n int64
	err := s.db.QueryRowContext(ctx, `
		SELECT COALESCE(count(*), 0)
		FROM messages m
		JOIN conversation_participants p
		  ON p.conversation_id = m.conversation_id AND p.user_id = $1
		WHERE m.is_deleted = false
		  AND m.sender_id <> $1
		  AND (p.last_read_at IS NULL OR m.created_at > p.last_read_at)`, userID).Scan(&n)
	return n, err
}

func (s *PostgresStore) ListMessages(ctx context.Context, userID, conversationID uuid.UUID, before *uuid.UUID, limit int) ([]Message, error) {
	var one int
	err := s.db.QueryRowContext(ctx, `
		SELECT 1 FROM conversation_participants
		WHERE conversation_id = $1 AND user_id = $2`, conversationID, userID).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}

	rows, err := s.db.QueryContext(ctx, `
		SELECT id, conversation_id, sender_id, content, created_at, edited_at, is_deleted
		FROM messages
		WHERE conversation_id = $1
		  AND ($3::uuid IS NULL OR created_at < (
		      SELECT created_at FROM messages
		      WHERE id = $3 AND conversation_id = $1))
		ORDER BY created_at DESC
		LIMIT $2`, conversationID, limit, before)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []Message
	for rows.Next() {
		var m Message
		if err := rows.Scan(&m.ID, &m.ConversationID, &m.SenderID, &m.Content,
			&m.CreatedAt, &m.EditedAt, &m.IsDeleted); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	// oldest → newest for the client
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out, nil
}

func (s *PostgresStore) CreateMessage(ctx context.Context, userID, conversationID uuid.UUID, content string) (Message, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return Message{}, err
	}
	defer tx.Rollback()

	var one int
	err = tx.QueryRowContext(ctx, `
		SELECT 1 FROM conversation_participants
		WHERE conversation_id = $1 AND user_id = $2`, conversationID, userID).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return Message{}, ErrNotFound
	}
	if err != nil {
		return Message{}, err
	}

	var m Message
	err = tx.QueryRowContext(ctx, `
		INSERT INTO messages (conversation_id, sender_id, content)
		VALUES ($1, $2, $3)
		RETURNING id, conversation_id, sender_id, content, created_at, edited_at, is_deleted`,
		conversationID, userID, content).
		Scan(&m.ID, &m.ConversationID, &m.SenderID, &m.Content, &m.CreatedAt, &m.EditedAt, &m.IsDeleted)
	if err != nil {
		return Message{}, err
	}
	if _, err := tx.ExecContext(ctx,
		`UPDATE conversations SET updated_at = now() WHERE id = $1`, conversationID); err != nil {
		return Message{}, err
	}
	// sending counts as reading your own thread: your unread never counts you
	if _, err := tx.ExecContext(ctx,
		`UPDATE conversation_participants SET last_read_at = now()
		 WHERE conversation_id = $1 AND user_id = $2`, conversationID, userID); err != nil {
		return Message{}, err
	}
	if err := tx.Commit(); err != nil {
		return Message{}, err
	}
	return m, nil
}

func (s *PostgresStore) MarkRead(ctx context.Context, userID, conversationID uuid.UUID) error {
	res, err := s.db.ExecContext(ctx, `
		UPDATE conversation_participants SET last_read_at = now()
		WHERE conversation_id = $1 AND user_id = $2`, conversationID, userID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) SoftDeleteMessage(ctx context.Context, userID, messageID uuid.UUID) error {
	var sender uuid.UUID
	err := s.db.QueryRowContext(ctx,
		`SELECT sender_id FROM messages WHERE id = $1 AND is_deleted = false`, messageID).Scan(&sender)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if sender != userID {
		return ErrForbidden
	}
	_, err = s.db.ExecContext(ctx,
		`UPDATE messages SET is_deleted = true, edited_at = now() WHERE id = $1`, messageID)
	return err
}

func (s *PostgresStore) ParticipantIDs(ctx context.Context, conversationID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT user_id FROM conversation_participants WHERE conversation_id = $1`, conversationID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

func nullableTime(t sql.NullTime) *time.Time {
	if !t.Valid {
		return nil
	}
	return &t.Time
}
