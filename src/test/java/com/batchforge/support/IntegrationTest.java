package com.batchforge.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import({TestcontainersConfiguration.class, MinioTestcontainersConfiguration.class})
public abstract class IntegrationTest {
}