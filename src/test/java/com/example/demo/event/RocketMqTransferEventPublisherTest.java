package com.example.demo.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RocketMqTransferEventPublisherTest {

    @Test
    void publishCompleted_sendsSerializedEventToTopic() throws Exception {
        var producer = mock(DefaultMQProducer.class);
        var mapper = new ObjectMapper();
        var publisher = new RocketMqTransferEventPublisher(producer, mapper, "transfer-events");
        var event = new TransferCompletedEvent(7L, "x", "y", new BigDecimal("5"));

        publisher.publishCompleted(event);

        var captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());
        Message sent = captor.getValue();
        assertThat(sent.getTopic()).isEqualTo("transfer-events");
        assertThat(mapper.readValue(sent.getBody(), TransferCompletedEvent.class)).isEqualTo(event);
    }
}
