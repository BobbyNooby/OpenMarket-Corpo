package dev.bob.openmarket.auth.events;

import dev.bob.openmarket.auth.domain.OutboxEvent;
import dev.bob.openmarket.auth.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drains the transactional outbox to Kafka. Business transactions only ever
 * INSERT outbox rows; this relay is the sole publisher, so "wrote to the DB"
 * and "an event will go out" are no longer coupled to each other's crashes.
 *
 * Delivery is at-least-once: the send is confirmed before published_at is
 * stamped, so a crash between the two replays the event. Consumers must be
 * idempotent (contracts/proto/openmarket/events/v1/user_events.proto).
 */
@Component
@ConditionalOnProperty(name = "auth.outbox.relay", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Duration sendTimeout;
    private final int retentionDays;

    public OutboxRelay(OutboxEventRepository outbox,
                       KafkaTemplate<String, String> kafka,
                       @org.springframework.beans.factory.annotation.Value("${auth.outbox.send-timeout-ms:5000}") long sendTimeoutMs,
                       @org.springframework.beans.factory.annotation.Value("${auth.outbox.retention-days:7}") int retentionDays) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.sendTimeout = Duration.ofMillis(sendTimeoutMs);
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${auth.outbox.poll-ms:2000}")
    public void relayPending() {
        List<OutboxEvent> pending = outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        if (pending.isEmpty()) {
            return;
        }
        int sent = 0;
        // Stop at the first failure rather than skipping ahead: rows are
        // oldest-first and the Kafka key is the user id, so plowing past a
        // stuck row could invert that user's event order.
        for (OutboxEvent event : pending) {
            try {
                kafka.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload())
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                Throwable cause = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
                log.warn("outbox: send failed for {} {} ({}: {}) — {} row(s) stay pending",
                    event.getTopic(), event.getId(),
                    cause == null ? e.getClass().getSimpleName() : cause.getClass().getSimpleName(),
                    cause == null ? "" : cause.getMessage(), pending.size() - sent);
                return;
            }
            event.setPublishedAt(Instant.now());
            outbox.save(event);
            sent++;
        }
        log.debug("outbox: relayed {} event(s)", sent);
    }

    /** Relayed rows are Kafka's problem now — keep the outbox table bounded. */
    @Scheduled(cron = "${auth.outbox.retention-cron:0 17 4 * * *}")
    @Transactional
    public void deleteRelayed() {
        long deleted = outbox.deleteByPublishedAtBefore(Instant.now().minus(Duration.ofDays(retentionDays)));
        if (deleted > 0) {
            log.info("outbox: retention deleted {} relayed event(s) older than {}d", deleted, retentionDays);
        }
    }
}
