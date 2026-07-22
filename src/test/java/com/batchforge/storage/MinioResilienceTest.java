package com.batchforge.storage;

import com.batchforge.support.PostgresContainerConfiguration;
import com.batchforge.support.RedisContainerConfiguration;
import io.minio.MinioClient;
import io.minio.errors.ServerException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@DirtiesContext
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class})
class MinioResilienceTest {

    @Autowired
    private MinioStorageService storageService;

    @MockitoBean
    private MinioClient minioClient;

    @Autowired
    io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry cbRegistry;

    @org.junit.jupiter.api.BeforeEach
    void resetBreaker() {
        cbRegistry.circuitBreaker("minio").reset();
    }

    @Test
    void getObjectRetriesOnTransientMinioFailure() throws Exception {
        when(minioClient.getObject(any()))
                .thenThrow(new ServerException("simulated MinIO outage", 503, null));

        assertThatThrownBy(() -> storageService.getObject("uploads/whatever.csv"))
                .isInstanceOf(StorageException.class);

        verify(minioClient, times(3)).getObject(any());
    }

    @Test
    void circuitBreakerOpensAfterRepeatedMinioFailures() throws Exception {
        when(minioClient.getObject(any()))
                .thenThrow(new ServerException("simulated MinIO outage", 503, null));

        io.github.resilience4j.circuitbreaker.CallNotPermittedException tripped = null;
        for (int i = 0; i < 40; i++) {
            try {
                storageService.getObject("uploads/whatever.csv");
            } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
                tripped = e;
                break;
            } catch (StorageException expected) {
            }
        }

        assertThat(tripped)
                .as("circuit breaker should open after the failure-rate threshold is exceeded")
                .isNotNull();

        int callsWhenOpen = mockingDetails(minioClient).getInvocations().size();
        assertThatThrownBy(() -> storageService.getObject("uploads/whatever.csv"))
                .isInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);
        assertThat(mockingDetails(minioClient).getInvocations().size())
                .as("an OPEN breaker must not invoke MinIO")
                .isEqualTo(callsWhenOpen);
    }
}