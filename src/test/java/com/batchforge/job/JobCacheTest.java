package com.batchforge.job;

import com.batchforge.common.error.ApiException;
import com.batchforge.organization.Organization;
import com.batchforge.organization.OrganizationRepository;
import com.batchforge.support.MinioTestcontainersConfiguration;
import com.batchforge.support.PostgresContainerConfiguration;
import com.batchforge.support.RedisContainerConfiguration;
import com.batchforge.user.Role;
import com.batchforge.user.User;
import com.batchforge.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class, MinioTestcontainersConfiguration.class})
class JobCacheTest {

    @Autowired
    private JobService jobService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    void terminalJobIsServedFromCacheAfterRowIsGone() {
        Ids ids = newOrgUser();
        Job job = new Job(ids.orgId(), ids.userId(), "key.csv");
        job.markCompleted();
        UUID jobId = jobRepository.save(job).getId();

        JobResponse first = jobService.getJob(jobId, ids.orgId());
        assertThat(first.status()).isEqualTo(JobStatus.COMPLETED);

        jobRepository.deleteById(jobId);

        JobResponse second = jobService.getJob(jobId, ids.orgId());
        assertThat(second.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(second.id()).isEqualTo(jobId);
    }

    @Test
    void nonTerminalJobIsNotCached() {
        Ids ids = newOrgUser();
        Job job = new Job(ids.orgId(), ids.userId(), "key.csv");
        UUID jobId = jobRepository.save(job).getId();

        assertThat(jobService.getJob(jobId, ids.orgId()).status()).isEqualTo(JobStatus.PENDING);

        jobRepository.deleteById(jobId);

        assertThatThrownBy(() -> jobService.getJob(jobId, ids.orgId()))
                .isInstanceOf(ApiException.class);
    }

    private Ids newOrgUser() {
        Organization org = organizationRepository.save(new Organization("Org"));
        String email = "user-" + UUID.randomUUID() + "@example.com";
        User user = userRepository.save(new User(email, "hash", org.getId(), Role.MEMBER));
        return new Ids(org.getId(), user.getId());
    }

    private record Ids(UUID orgId, UUID userId) {}
}