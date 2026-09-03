package main

import (
	"context"
	"database/sql"
	"fmt"
)

// Embedded schema migrations — the service owns messaging_db end to end.
// V1 is the v1-parity domain; cross-service references (listing_id) are
// plain uuid columns with NO foreign key: catalogue owns listings, this
// service only remembers the pointer (database-per-service).
var migrations = []string{
	// V1 — conversations, participants, messages
	`CREATE TABLE conversations (
		id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
		created_at  timestamptz NOT NULL DEFAULT now(),
		updated_at  timestamptz NOT NULL DEFAULT now(),
		listing_id  uuid
	);
	CREATE INDEX idx_conversations_updated ON conversations (updated_at);

	CREATE TABLE conversation_participants (
		conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
		user_id         uuid NOT NULL,
		joined_at       timestamptz NOT NULL DEFAULT now(),
		last_read_at    timestamptz,
		PRIMARY KEY (conversation_id, user_id)
	);
	CREATE INDEX idx_participants_user ON conversation_participants (user_id);

	CREATE TABLE messages (
		id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
		conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
		sender_id       uuid NOT NULL,
		content         text NOT NULL,
		created_at      timestamptz NOT NULL DEFAULT now(),
		edited_at       timestamptz,
		is_deleted      boolean NOT NULL DEFAULT false
	);
	CREATE INDEX idx_messages_conv_created ON messages (conversation_id, created_at);`,
}

// migrate applies pending migrations in order, each in its own transaction,
// recorded in schema_migrations.
func migrate(ctx context.Context, db *sql.DB) error {
	if _, err := db.ExecContext(ctx, `
		CREATE TABLE IF NOT EXISTS schema_migrations (
			version    int PRIMARY KEY,
			applied_at timestamptz NOT NULL DEFAULT now()
		)`); err != nil {
		return fmt.Errorf("migrations table: %w", err)
	}
	for i, stmt := range migrations {
		version := i + 1
		var done bool
		err := db.QueryRowContext(ctx,
			`SELECT true FROM schema_migrations WHERE version = $1`, version).Scan(&done)
		if err == nil {
			continue // already applied
		} else if err != sql.ErrNoRows {
			return err
		}
		tx, err := db.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, stmt); err != nil {
			tx.Rollback()
			return fmt.Errorf("migration V%d: %w", version, err)
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO schema_migrations (version) VALUES ($1)`, version); err != nil {
			tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	return nil
}
