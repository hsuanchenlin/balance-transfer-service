package com.example.demo.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Active when RocketMQ is enabled: publishes transfer events, best-effort. */
@Component
@ConditionalOnProperty(name = "rocketmq.enabled", havingValue = "true")
public class RocketMqTransferEventPublisher implements TransferEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RocketMqTransferEventPublisher.class);

    private final DefaultMQProducer producer;
    private final ObjectMapper mapper;
    private final String topic;

    public RocketMqTransferEventPublisher(DefaultMQProducer producer, ObjectMapper mapper,
                                          @Value("${rocketmq.topic.transfer}") String topic) {
        this.producer = producer;
        this.mapper = mapper;
        this.topic = topic;
    }

    @Override
    public void publishCompleted(TransferCompletedEvent event) {
        send(TransferEventHandler.COMPLETED_EVENT_TYPE, event, event.transferId());
    }

    @Override
    public void publishCancelled(TransferCancelledEvent event) {
        send(TransferEventHandler.CANCELLED_EVENT_TYPE, event, event.transferId());
    }

    private void send(String tag, Object event, long transferId) {
        try {
            byte[] body = mapper.writeValueAsBytes(event);
            producer.send(new Message(topic, tag, body));
        } catch (Exception e) {
            // Best-effort: the transfer is already committed — a messaging failure
            // must never fail it. A real system would fall back to a transactional
            // outbox; here we log and move on.
            log.warn("Failed to publish {} for transfer {}", tag, transferId, e);
        }
    }
}
