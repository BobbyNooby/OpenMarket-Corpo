package dev.bob.openmarket.auth.repository;

import dev.bob.openmarket.auth.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Oldest-first so per-user event order survives relay batching (Kafka
     * key = user id → one partition per user).
     */
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

    /** Retention: relayed rows are kafka's problem now. */
    long deleteByPublishedAtBefore(Instant cutoff);
}
