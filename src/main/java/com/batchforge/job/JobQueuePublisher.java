package com.batchforge.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class JobQueuePublisher {

    private final RabbitTemplate rabbitTemplate;

    public JobQueuePublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobQueued(JobQueuedEvent event) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitConfig.JOBS_EXCHANGE, RabbitConfig.IMPORT_ROUTING_KEY, new JobMessage(event.jobId()));
        } catch (AmqpException e) {
            log.error("Failed to publish queued job {} to RabbitMQ; it remains QUEUED for recovery", event.jobId(), e);
        }
    }
}