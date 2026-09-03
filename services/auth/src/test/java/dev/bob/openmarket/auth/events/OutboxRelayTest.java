package dev.bob.openmarket.auth.events;

import dev.bob.openmarket.auth.domain.OutboxEvent;
import dev.bob.openmarket.auth.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The relay's contract: pending outbox rows go to their topic keyed by user
 * id and are stamped only after the send is acknowledged; a broker failure
 * stops the batch (order is per-user) and leaves every row pending; rows
 * already relayed are retention's problem, not the relay's.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock OutboxEventRepository outbox;
    @Mock KafkaTemplate<String, String> kafka;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(outbox, kafka, 1000, 7);
    }

    private OutboxEvent pending(String topic, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("user");
        event.setAggregateId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        event.setTopic(topic);
        event.setPayload(payload);
        return event;
    }

    @Test
    void publishes_pending_events_to_their_topics_keyed_by_user_id_then_stamps_them() {
        OutboxEvent ban = pending("user.banned", "{\"userId\":\"u\",\"reason\":\"\"}");
        OutboxEvent roles = pending("user.roles_changed", "{\"userId\":\"u\",\"newRoles\":[]}");
        when(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(ban, roles));
        when(kafka.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));

        relay.relayPending();

        verify(kafka).send("user.banned", ban.getAggregateId().toString(), ban.getPayload());
        verify(kafka).send("user.roles_changed", roles.getAggregateId().toString(), roles.getPayload());
        ArgumentCaptor<OutboxEvent> stamped = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox, org.mockito.Mockito.times(2)).save(stamped.capture());
        assertThat(stamped.getAllValues()).extracting(OutboxEvent::getPublishedAt)
            .allSatisfy(p -> assertThat(p).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS)));
    }

    @Test
    void a_failed_send_stops_the_batch_and_stamps_nothing() {
        OutboxEvent first = pending("user.banned", "{\"userId\":\"u\"}");
        OutboxEvent second = pending("user.roles_changed", "{\"userId\":\"u\"}");
        when(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafka.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.relayPending();

        // one attempt, no stamp, nothing lost — the next tick retries both
        verify(kafka).send(anyString(), anyString(), anyString());
        verify(outbox, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void a_send_that_never_acknowledges_times_out_and_stays_pending() {
        OutboxEvent event = pending("user.deleted", "{\"userId\":\"u\",\"erased\":true}");
        when(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        CompletableFuture<Object> never = new CompletableFuture<>();
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(inv -> never);

        relay.relayPending();

        verify(outbox, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void does_nothing_when_the_outbox_is_drained() {
        when(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of());

        relay.relayPending();

        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void retention_deletes_relayed_rows_older_than_the_cutoff() {
        relay.deleteRelayed();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(outbox).deleteByPublishedAtBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isCloseTo(Instant.now().minus(7, ChronoUnit.DAYS), within(10, ChronoUnit.SECONDS));
    }
}
