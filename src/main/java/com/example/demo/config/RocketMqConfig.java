package com.example.demo.config;

import com.example.demo.event.TransferCancelledEvent;
import com.example.demo.event.TransferCompletedEvent;
import com.example.demo.event.TransferEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the raw RocketMQ producer + push consumer. Gated on {@code rocketmq.enabled}
 * so the integration test suite (which has no host-reachable broker) never starts it.
 */
@Configuration
@ConditionalOnProperty(name = "rocketmq.enabled", havingValue = "true")
public class RocketMqConfig {

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer transferProducer(@Value("${rocketmq.name-server}") String nameServer,
                                              @Value("${rocketmq.producer.group}") String group) {
        DefaultMQProducer producer = new DefaultMQProducer(group);
        producer.setNamesrvAddr(nameServer);
        return producer;
    }

    @Bean(destroyMethod = "shutdown")
    public DefaultMQPushConsumer transferConsumer(@Value("${rocketmq.name-server}") String nameServer,
                                                  @Value("${rocketmq.topic.transfer}") String topic,
                                                  TransferEventHandler handler,
                                                  ObjectMapper mapper) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("balance-transfer-consumer");
        consumer.setNamesrvAddr(nameServer);
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            try {
                for (var message : messages) {
                    // Route by the message tag set by the publisher.
                    if (TransferEventHandler.CANCELLED_EVENT_TYPE.equals(message.getTags())) {
                        handler.handleCancelled(
                                mapper.readValue(message.getBody(), TransferCancelledEvent.class));
                    } else {
                        handler.handle(
                                mapper.readValue(message.getBody(), TransferCompletedEvent.class));
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (Exception e) {
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        });
        consumer.start();
        return consumer;
    }
}
