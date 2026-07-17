package com.batchforge.job;

import com.batchforge.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class JobQueueTopologyTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void publishesAndReceivesJobMessage() {
        UUID jobId = UUID.randomUUID();

        rabbitTemplate.convertAndSend(RabbitConfig.JOBS_EXCHANGE, RabbitConfig.IMPORT_ROUTING_KEY, new JobMessage(jobId));

        JobMessage received = rabbitTemplate.receiveAndConvert(
                RabbitConfig.IMPORT_QUEUE, 5_000, new ParameterizedTypeReference<JobMessage>() {});

        assertThat(received).isNotNull();
        assertThat(received.jobId()).isEqualTo(jobId);
    }
}