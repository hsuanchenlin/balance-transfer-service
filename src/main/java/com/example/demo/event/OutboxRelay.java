package com.example.demo.event;

import com.example.demo.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Delivery half of the transactional outbox: a scheduled poller that reads due
 * unpublished rows in id order, sends each through {@link OutboxMessageSender},
 * and stamps {@code published_at}. A failed send defers only that row (attempts
 * incremented, capped exponential backoff via {@code next_attempt_at}) and the
 * relay moves on, so one poisoned event cannot block the queue.
 *
 * <p><b>Delivery semantics: at-least-once.</b> A crash between the send and
 * {@code markPublished} re-sends the row on the next pass; the consumer is
 * idempotent on {@code UNIQUE(event_type, transfer_id)}, so duplicates collapse.
 *
 * <p><b>Scheduling and locking.</b> {@code @Scheduled} is enabled by
 * {@link com.example.demo.config.SchedulingConfig}, gated on
 * {@code outbox.relay.enabled} so tests can drive {@link #relayOnce()}
 * deterministically. Spring's default single-threaded scheduler is sufficient -
 * this is the only scheduled job, so no {@code SchedulingConfigurer} executor
 * tuning is needed, and {@code fixedDelay} guarantees passes never overlap within
 * one instance. With multiple app instances every relay polls the same table:
 * correctness holds (worst case is a duplicate send, absorbed by the idempotent
 * consumer) but the wasted sends would be avoided in production with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} row claims or a scheduler lock such
 * as ShedLock.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final OutboxMessageSender sender;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outbox, OutboxMessageSender sender,
                       @Value("${outbox.relay.batch-size:100}") int batchSize) {
        this.outbox = outbox;
        this.sender = sender;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:1000}")
    public void poll() {
        relayOnce();
    }

    /**
     * One relay pass over the due backlog. Returns the number of rows published,
     * mainly so tests can assert on a pass directly.
     */
    public int relayOnce() {
        List<OutboxEvent> due = outbox.findDue(batchSize);
        int published = 0;
        for (OutboxEvent event : due) {
            try {
                sender.send(event.eventType(), event.payload());
                outbox.markPublished(event.id());
                published++;
            } catch (Exception e) {
                outbox.markFailed(event.id());
                log.warn("Outbox publish failed for event {} ({}), attempt {}; deferred for retry",
                        event.id(), event.eventType(), event.attempts() + 1, e);
            }
        }
        return published;
    }
}
