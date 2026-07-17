package com.batchforge.job;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String JOBS_EXCHANGE = "batchforge.jobs";
    public static final String IMPORT_QUEUE = "batchforge.jobs.import";
    public static final String IMPORT_ROUTING_KEY = "job.import";

    public static final String DEAD_LETTER_EXCHANGE = "batchforge.jobs.dlx";
    public static final String DEAD_LETTER_QUEUE = "batchforge.jobs.import.dlq";
    public static final String DEAD_LETTER_ROUTING_KEY = "job.import.dlq";

    @Bean
    DirectExchange jobsExchange() {
        return new DirectExchange(JOBS_EXCHANGE);
    }

    @Bean
    Queue importQueue() {
        return QueueBuilder.durable(IMPORT_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    Binding importBinding() {
        return BindingBuilder.bind(importQueue()).to(jobsExchange()).with(IMPORT_ROUTING_KEY);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE);
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}