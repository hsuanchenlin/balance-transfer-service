package com.example.demo.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} processing for the outbox relay - the only
 * scheduled job, so the default single-threaded scheduler is fine (see
 * {@link com.example.demo.event.OutboxRelay} for the scheduling/locking notes).
 * Gated on {@code outbox.relay.enabled} (default on) so the integration test
 * suite can switch the background poller off and drive relay passes
 * deterministically.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
