package com.example.demo.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Active when RocketMQ is disabled (default / tests): the relay still drains the
 * outbox - rows are marked published without a broker - so transfers keep working
 * and the table does not grow unboundedly. Same contract as the old no-op
 * publisher: messaging off means events go nowhere, deliberately.
 */
@Component
@ConditionalOnProperty(name = "rocketmq.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpOutboxSender implements OutboxMessageSender {

    @Override
    public void send(String eventType, String payload) {
        // messaging disabled
    }
}
