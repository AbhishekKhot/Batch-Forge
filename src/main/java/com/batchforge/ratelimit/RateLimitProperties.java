package com.batchforge.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("batchforge.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100") long capacity,
        @DefaultValue("100") long refillTokens,
        @DefaultValue("PT1M") Duration refillPeriod,
        @DefaultValue Auth auth) {

    public record Auth(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10") long capacity,
            @DefaultValue("10") long refillTokens,
            @DefaultValue("PT1M") Duration refillPeriod) {
    }
}