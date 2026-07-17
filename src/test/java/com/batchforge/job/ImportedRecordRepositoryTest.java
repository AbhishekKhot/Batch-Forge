package com.batchforge.job;

import com.batchforge.organization.Organization;
import com.batchforge.organization.OrganizationRepository;
import com.batchforge.support.PostgresContainerConfiguration;
import com.batchforge.user.Role;
import com.batchforge.user.User;
import com.batchforge.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class ImportedRecordRepositoryTest {

    @Autowired
    private ImportedRecordRepository repository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JobRepository jobRepository;

    @Test
    void batchInsertPersistsAllRows() {
        UUID jobId = seedJob();

        repository.insertBatch(List.of(row(jobId, 1), row(jobId, 2), row(jobId, 3)));

        assertThat(repository.countByJobId(jobId)).isEqualTo(3L);
    }

    @Test
    void duplicateSourceRowsAreIgnoredOnReinsert() {
        UUID jobId = seedJob();

        repository.insertBatch(List.of(row(jobId, 1), row(jobId, 2)));
        repository.insertBatch(List.of(row(jobId, 2), row(jobId, 3)));

        assertThat(repository.countByJobId(jobId)).isEqualTo(3L);
    }

    private UUID seedJob() {
        Organization org = organizationRepository.save(new Organization("Org"));
        User user = userRepository.save(
                new User("user-" + UUID.randomUUID() + "@example.com", "hash", org.getId(), Role.MEMBER));
        Job job = jobRepository.save(
                new Job(org.getId(), user.getId(), org.getId() + "/" + UUID.randomUUID() + "/source.csv"));
        return job.getId();
    }

    private ImportedRecord row(UUID jobId, long rowNumber) {
        return new ImportedRecord(jobId, rowNumber,
                "c" + rowNumber + "@example.com", "First" + rowNumber, "Last" + rowNumber, null, "hash" + rowNumber);
    }
}