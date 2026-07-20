package com.batchforge.job;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.slf4j.MDC;
import java.util.UUID;

@Component
public class JobConsumer {

    private final JobProcessingService processing;
    private final ImportProcessor importProcessor;

    public JobConsumer(JobProcessingService processing, ImportProcessor importProcessor) {
        this.processing = processing;
        this.importProcessor = importProcessor;
    }

    @RabbitListener(queues = RabbitConfig.IMPORT_QUEUE)
    public void onJobMessage(JobMessage message) {
        UUID jobId = message.jobId();
        MDC.put("correlationId", jobId.toString());
        try {
            if (!processing.claim(jobId)) {
                return;
            }
            importProcessor.process(jobId);
            processing.complete(jobId);
        } finally {
            MDC.remove("correlationId");
        }
    }
}