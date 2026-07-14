package com.batchforge.storage;

import com.batchforge.support.MinioTestcontainersConfiguration;
import com.batchforge.support.PostgresContainerConfiguration;
import com.batchforge.support.RedisContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class, MinioTestcontainersConfiguration.class})
class MinioStorageServiceTest {

    @Autowired
    private MinioStorageService storageService;

    @Test
    void presignedUrlAllowsUploadAndObjectIsThenVisible() throws Exception {
        String key = "org-1/job-1/source.csv";
        byte[] content = "id,name\n1,alice\n".getBytes(StandardCharsets.UTF_8);

        String url = storageService.presignedUploadUrl(key);
        assertThat(url).startsWith("http");

        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);

        assertThat(storageService.objectSize(key)).contains((long) content.length);
    }

    @Test
    void objectSizeIsEmptyForMissingObject() {
        assertThat(storageService.objectSize("nope/missing.csv")).isEmpty();
    }
}