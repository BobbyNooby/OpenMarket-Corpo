package blocklist

import (
	"context"
	"log/slog"
)

// Event is the consumer-neutral shape of one Kafka record.
type Event struct {
	Topic string
	Value []byte
}

// Reader is the slice of a Kafka consumer this package needs — production
// adapts segmentio/kafka-go, tests a fake.
type Reader interface {
	Next(ctx context.Context) (Event, error)
	Commit(ctx context.Context, ev Event) error
}

// Consume loops until ctx is done, applying every event. Commit happens
// only after Apply succeeds, so a poisoned record would wedge the group —
// hence Apply errors are logged and committed anyway: a malformed payload
// must not block the partition, and the blocklist is not authoritative.
func Consume(ctx context.Context, r Reader, bl *Blocklist, logger *slog.Logger) {
	for {
		ev, err := r.Next(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			logger.Warn("blocklist: read failed", "err", err)
			continue
		}
		if err := bl.Apply(ctx, ev.Topic, ev.Value); err != nil {
			logger.Warn("blocklist: apply failed", "topic", ev.Topic, "err", err)
		}
		if err := r.Commit(ctx, ev); err != nil && ctx.Err() == nil {
			logger.Warn("blocklist: commit failed — event will redeliver", "topic", ev.Topic, "err", err)
		}
	}
}
