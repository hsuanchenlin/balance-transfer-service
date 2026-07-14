package com.example.demo.event;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RocketMqOutboxSenderTest {

    @Test
    void send_targetsTopicWithEventTypeTagAndPayloadBody() throws Exception {
        var producer = mock(DefaultMQProducer.class);
        var sender = new RocketMqOutboxSender(producer, "transfer-events");

        sender.send("TransferCompleted", "{\"transferId\":7}");

        var captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());
        Message sent = captor.getValue();
        assertThat(sent.getTopic()).isEqualTo("transfer-events");
        assertThat(sent.getTags()).isEqualTo("TransferCompleted");
        assertThat(new String(sent.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"transferId\":7}");
    }

    @Test
    void send_propagatesBrokerFailure_soTheRelayCanDeferTheRow() throws Exception {
        var producer = mock(DefaultMQProducer.class);
        doThrow(new IllegalStateException("broker down")).when(producer).send(any(Message.class));
        var sender = new RocketMqOutboxSender(producer, "transfer-events");

        assertThatThrownBy(() -> sender.send("TransferCompleted", "{}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
