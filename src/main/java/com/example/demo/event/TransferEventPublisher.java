package com.example.demo.event;

public interface TransferEventPublisher {
    void publishCompleted(TransferCompletedEvent event);

    void publishCancelled(TransferCancelledEvent event);
}
