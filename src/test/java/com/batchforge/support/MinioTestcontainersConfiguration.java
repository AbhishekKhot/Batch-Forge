package com.batchforge.support;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class MinioTestcontainersConfiguration {

    private static final int MINIO_PORT = 9000;

    @Bean
    GenericContainer<?> minioContainer() {
        return new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
                .withExposedPorts(MINIO_PORT)
                .withEnv("MINIO_ROOT_USER", "batchforge")
                .withEnv("MINIO_ROOT_PASSWORD", "batchforge")
                .withCommand("server", "/data");
    }

    @Bean
    DynamicPropertyRegistrar minioProperties(@Qualifier("minioContainer") GenericContainer<?> minioContainer) {
        return registry -> {
            registry.add("batchforge.storage.endpoint",
                    () -> "http://" + minioContainer.getHost() + ":" + minioContainer.getMappedPort(MINIO_PORT));
            registry.add("batchforge.storage.access-key", () -> "batchforge");
            registry.add("batchforge.storage.secret-key", () -> "batchforge");
            registry.add("batchforge.storage.bucket", () -> "batchforge-test");
        };
    }
}