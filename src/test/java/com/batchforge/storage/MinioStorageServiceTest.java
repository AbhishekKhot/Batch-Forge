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
import java.io.InputStream;
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

    @Test
    void getObjectReturnsUploadedBytes() throws Exception {
        String key = "org-2/job-2/source.csv";
        byte[] content = "email,first_name\nz@x.com,Zed\n".getBytes(StandardCharsets.UTF_8);

        HttpResponse<Void> put = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(storageService.presignedUploadUrl(key)))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(200);

        try (InputStream in = storageService.getObject(key)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }
}