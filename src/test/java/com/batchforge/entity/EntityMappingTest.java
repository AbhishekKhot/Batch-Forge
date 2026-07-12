package com.batchforge.entity;

import com.batchforge.job.Job;
import com.batchforge.job.JobStatus;
import com.batchforge.job.JobType;
import com.batchforge.organization.Organization;
import com.batchforge.user.Role;
import com.batchforge.user.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(EntityMappingTest.PostgresConfig.class)
class EntityMappingTest {

    // Real Postgres via @ServiceConnection so Flyway builds the schema and
    // ddl-auto=validate checks these mappings against the actual V1 migration.
    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfig {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
        }
    }

    @Autowired
    private EntityManager em;

    @Test
    void persistsAndReadsFullGraph() {
        Organization org = new Organization("Acme");
        em.persist(org);

        User user = new User("owner@example.com", "hash", org.getId(), Role.ORG_OWNER);
        em.persist(user);

        Job job = new Job(org.getId(), user.getId(), "uploads/data.csv");
        em.persist(job);

        em.flush();
        em.clear();

        Organization foundOrg = em.find(Organization.class, org.getId());
        assertThat(foundOrg.getId()).isNotNull();
        assertThat(foundOrg.getName()).isEqualTo("Acme");
        assertThat(foundOrg.getCreatedAt()).isNotNull();

        User foundUser = em.find(User.class, user.getId());
        assertThat(foundUser.getEmail()).isEqualTo("owner@example.com");
        assertThat(foundUser.getRole()).isEqualTo(Role.ORG_OWNER);
        assertThat(foundUser.getOrgId()).isEqualTo(org.getId());
        assertThat(foundUser.getCreatedAt()).isNotNull();

        Job foundJob = em.find(Job.class, job.getId());
        assertThat(foundJob.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(foundJob.getJobType()).isEqualTo(JobType.CSV_IMPORT);
        assertThat(foundJob.getSourceObjectKey()).isEqualTo("uploads/data.csv");
        assertThat(foundJob.getResultObjectKey()).isNull();
        assertThat(foundJob.getErrorReportObjectKey()).isNull();
        assertThat(foundJob.getTotalRows()).isNull();
        assertThat(foundJob.getProcessedRows()).isZero();
        assertThat(foundJob.getFailedRows()).isZero();
        assertThat(foundJob.getLastProcessedRow()).isZero();
        assertThat(foundJob.getRetryCount()).isZero();
        assertThat(foundJob.getVersion()).isZero();
        assertThat(foundJob.getCreatedAt()).isNotNull();
        assertThat(foundJob.getUpdatedAt()).isNotNull();
    }

    @Test
    void incrementsVersionOnStatusTransition() {
        Organization org = new Organization("Globex");
        em.persist(org);
        User user = new User("u@example.com", "hash", org.getId(), Role.MEMBER);
        em.persist(user);
        Job job = new Job(org.getId(), user.getId(), "uploads/x.csv");
        em.persist(job);
        em.flush();

        long initialVersion = job.getVersion();

        job.markProcessing();
        em.flush();

        assertThat(job.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(job.getVersion()).isEqualTo(initialVersion + 1);
    }
}