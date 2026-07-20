package com.batchforge.job;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class JobConsumerMdcTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void putsJobIdInMdcDuringProcessingAndClearsAfter() {
        JobProcessingService processing = Mockito.mock(JobProcessingService.class);
        ImportProcessor importProcessor = Mockito.mock(ImportProcessor.class);
        JobConsumer consumer = new JobConsumer(processing, importProcessor);

        UUID jobId = UUID.randomUUID();
        when(processing.claim(jobId)).thenReturn(true);

        String[] mdcDuringProcess = new String[1];
        Mockito.doAnswer(inv -> {
            mdcDuringProcess[0] = MDC.get("correlationId");
            return null;
        }).when(importProcessor).process(jobId);

        consumer.onJobMessage(new JobMessage(jobId));

        assertThat(mdcDuringProcess[0])
                .as("worker should log under the job id as correlationId")
                .isEqualTo(jobId.toString());
        assertThat(MDC.get("correlationId"))
                .as("MDC cleared after the message (listener threads are pooled)")
                .isNull();
    }
}