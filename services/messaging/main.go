// Command messaging is OpenMarket's chat service: conversations, messages,
// unread tracking, and the server→client WebSocket push channel. All writes
// ride REST; the socket only fans out what the store already committed.
package main

import (
	"context"
	"database/sql"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	_ "github.com/lib/pq"
)

func envOrDefault(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func envList(key string) []string {
	v := os.Getenv(key)
	if v == "" {
		return nil
	}
	return strings.Split(v, ",")
}

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	dbURL := os.Getenv("DATABASE_URL")
	if dbURL == "" {
		logger.Error("DATABASE_URL is required")
		os.Exit(1)
	}
	db, err := sql.Open("postgres", dbURL)
	if err != nil {
		logger.Error("bad DATABASE_URL", "err", err)
		os.Exit(1)
	}
	db.SetMaxOpenConns(10)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(30 * time.Minute)
	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	if err := db.PingContext(pingCtx); err != nil {
		cancel()
		logger.Error("postgres unreachable at boot", "err", err)
		os.Exit(1)
	}
	cancel()
	if err := migrate(ctx, db); err != nil {
		logger.Error("migrations failed", "err", err)
		os.Exit(1)
	}

	verifier := NewJWKSVerifier(envOrDefault("AUTH_URL", "http://localhost:8080") + "/.well-known/jwks.json")
	store := NewPostgresStore(db)
	a := newApp(store, verifier, NewHub(), logger, envList("WS_ALLOWED_ORIGINS"))

	srv := &http.Server{
		Addr:              ":" + envOrDefault("PORT", "8082"),
		Handler:           a.routes(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       30 * time.Second,
		IdleTimeout:       120 * time.Second,
		// No WriteTimeout: WS connections are long-lived by design.
	}

	go func() {
		logger.Info("messaging listening", "port", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Error("messaging exited", "err", err)
			os.Exit(1)
		}
	}()

	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Error("shutdown", "err", err)
	}
	logger.Info("messaging stopped cleanly")
}
