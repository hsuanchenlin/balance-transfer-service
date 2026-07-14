package com.example.demo.event;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Active when RocketMQ is enabled: sends an outbox row to the transfer topic, with
 * the event type as the message tag (the consumer routes by it). Send failures
 * propagate to the relay, which defers the row and retries - unlike the old
 * direct publisher, nothing here is best-effort.
 */
@Component
@ConditionalOnProperty(name = "rocketmq.enabled", havingValue = "true")
public class RocketMqOutboxSender implements OutboxMessageSender {

    private final DefaultMQProducer producer;
    private final String topic;

    public RocketMqOutboxSender(DefaultMQProducer producer,
                                @Value("${rocketmq.topic.transfer}") String topic) {
        this.producer = producer;
        this.topic = topic;
    }

    @Override
    public void send(String eventType, String payload) throws Exception {
        producer.send(new Message(topic, eventType, payload.getBytes(StandardCharsets.UTF_8)));
    }
}
