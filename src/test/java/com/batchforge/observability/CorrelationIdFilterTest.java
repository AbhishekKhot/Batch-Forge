package com.batchforge.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesCorrelationIdWhenAbsentAndEchoesIt() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] mdcDuringChain = new String[1];
        FilterChain chain = (req, res) -> mdcDuringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY);

        filter.doFilter(request, response, chain);

        assertThat(mdcDuringChain[0]).as("correlationId present in MDC during the request").isNotBlank();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER))
                .as("same id echoed on the response").isEqualTo(mdcDuringChain[0]);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).as("MDC cleared after the request").isNull();
    }

    @Test
    void reusesIncomingCorrelationIdHeader() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] mdcDuringChain = new String[1];
        FilterChain chain = (req, res) -> mdcDuringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY);

        filter.doFilter(request, response, chain);

        assertThat(mdcDuringChain[0]).isEqualTo("abc-123");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123");
    }
}