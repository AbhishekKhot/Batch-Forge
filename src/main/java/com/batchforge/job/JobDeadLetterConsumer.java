package com.batchforge.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.slf4j.MDC;

@Slf4j
@Component
public class JobDeadLetterConsumer {

    private final JobProcessingService processing;

    public JobDeadLetterConsumer(JobProcessingService processing) {
        this.processing = processing;
    }

    @RabbitListener(queues = RabbitConfig.DEAD_LETTER_QUEUE)
    public void onDeadLetter(JobMessage message) {
        MDC.put("correlationId", message.jobId().toString());
        try {
            log.warn("Job {} exhausted processing retries; marking FAILED", message.jobId());
            processing.markFailed(message.jobId());
        } finally {
            MDC.remove("correlationId");
        }
    }
}