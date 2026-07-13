package com.example.demo.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Active when RocketMQ is disabled (default / tests): transfers still work, no message is sent. */
@Component
@ConditionalOnProperty(name = "rocketmq.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpTransferEventPublisher implements TransferEventPublisher {

    @Override
    public void publishCompleted(TransferCompletedEvent event) {
        // messaging disabled
    }

    @Override
    public void publishCancelled(TransferCancelledEvent event) {
        // messaging disabled
    }
}
