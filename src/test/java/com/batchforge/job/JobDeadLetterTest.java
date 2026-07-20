package com.batchforge.job;

import com.batchforge.organization.Organization;
import com.batchforge.organization.OrganizationRepository;
import com.batchforge.support.MinioTestcontainersConfiguration;
import com.batchforge.support.PostgresContainerConfiguration;
import com.batchforge.support.RedisContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.batchforge.user.Role;
import com.batchforge.user.User;
import com.batchforge.user.UserRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class, MinioTestcontainersConfiguration.class})
class JobDeadLetterTest {

    @Autowired private JobDeadLetterConsumer deadLetterConsumer;
    @Autowired private JobProcessingService processing;
    @Autowired private JobRepository jobRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;

    @MockitoBean private RabbitTemplate rabbitTemplate;

    @Test
    void deadLetteredJobIsMarkedFailed() {
        UUID jobId = queuedJob();

        deadLetterConsumer.onDeadLetter(new JobMessage(jobId));

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(JobStatus.FAILED);
    }

    @Test
    void deadLetterDoesNotOverrideACompletedJob() {
        UUID jobId = queuedJob();
        processing.claim(jobId);
        processing.complete(jobId);

        deadLetterConsumer.onDeadLetter(new JobMessage(jobId));

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(JobStatus.COMPLETED);
    }

    private UUID queuedJob() {
        Organization org = organizationRepository.save(new Organization("Org"));
        String email = "user-" + UUID.randomUUID() + "@example.com";
        User user = userRepository.save(new User(email, "hash", org.getId(), Role.MEMBER));
        Job job = new Job(org.getId(), user.getId(), "key.csv");
        job.markQueued();
        return jobRepository.save(job).getId();
    }
}