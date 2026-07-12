package com.batchforge.job;

import com.batchforge.organization.Organization;
import com.batchforge.support.PostgresContainerConfiguration;
import com.batchforge.user.Role;
import com.batchforge.user.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresContainerConfiguration.class)
class JobRepositoryTest {

    // Unique tiebreaker (id) makes the sort total, so pagination is stable across page queries.
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    @Autowired
    private EntityManager em;

    @Autowired
    private JobRepository jobRepository;

    @Test
    void paginatesJobsForOrgNewestFirst() {
        OrgUser a = persistOrgWithUser("Acme", "a@example.com");
        for (int i = 0; i < 5; i++) {
            persistJob(a.orgId(), a.userId(), "uploads/" + i + ".csv");
        }
        em.clear();

        Page<Job> firstPage = jobRepository.findByOrgId(a.orgId(), PageRequest.of(0, 2, NEWEST_FIRST));
        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
        assertThat(firstPage.getNumber()).isZero();
        assertThat(firstPage.getContent()).hasSize(2);

        List<Job> all = new ArrayList<>();
        for (int p = 0; p < firstPage.getTotalPages(); p++) {
            all.addAll(jobRepository.findByOrgId(a.orgId(), PageRequest.of(p, 2, NEWEST_FIRST)).getContent());
        }
        assertThat(all).hasSize(5);
        assertThat(all).extracting(Job::getId).doesNotHaveDuplicates();
        assertThat(all).extracting(Job::getCreatedAt)
                .isSortedAccordingTo(Comparator.<Instant>reverseOrder());
    }

    @Test
    void filtersJobsByStatus() {
        OrgUser a = persistOrgWithUser("Acme", "a@example.com");
        persistJob(a.orgId(), a.userId(), "uploads/pending-1.csv");
        persistJob(a.orgId(), a.userId(), "uploads/pending-2.csv");
        Job processing = persistJob(a.orgId(), a.userId(), "uploads/processing-1.csv");
        processing.markProcessing();
        em.flush();
        em.clear();

        Pageable page = PageRequest.of(0, 20, NEWEST_FIRST);

        Page<Job> pending = jobRepository.findByOrgIdAndStatus(a.orgId(), JobStatus.PENDING, page);
        assertThat(pending.getTotalElements()).isEqualTo(2);
        assertThat(pending.getContent())
                .allSatisfy(j -> assertThat(j.getStatus()).isEqualTo(JobStatus.PENDING));

        Page<Job> proc = jobRepository.findByOrgIdAndStatus(a.orgId(), JobStatus.PROCESSING, page);
        assertThat(proc.getTotalElements()).isEqualTo(1);
        Job only = proc.getContent().get(0);
        assertThat(only.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(only.getSourceObjectKey()).isEqualTo("uploads/processing-1.csv");
    }

    @Test
    void doesNotReturnJobsFromAnotherOrg() {
        OrgUser a = persistOrgWithUser("Acme", "a@example.com");
        OrgUser b = persistOrgWithUser("Globex", "b@example.com");

        persistJob(a.orgId(), a.userId(), "uploads/a-1.csv");
        persistJob(a.orgId(), a.userId(), "uploads/a-2.csv");
        Job bJob = persistJob(b.orgId(), b.userId(), "uploads/b-1.csv");
        em.clear();

        Page<Job> aJobs = jobRepository.findByOrgId(a.orgId(), PageRequest.of(0, 20, NEWEST_FIRST));

        assertThat(aJobs.getTotalElements()).isEqualTo(2);
        assertThat(aJobs.getContent())
                .allSatisfy(j -> assertThat(j.getOrgId()).isEqualTo(a.orgId()));
        assertThat(aJobs.getContent()).extracting(Job::getId).doesNotContain(bJob.getId());
    }

    private record OrgUser(UUID orgId, UUID userId) {}

    private OrgUser persistOrgWithUser(String orgName, String email) {
        Organization org = new Organization(orgName);
        em.persist(org);
        User user = new User(email, "hash", org.getId(), Role.MEMBER);
        em.persist(user);
        em.flush();
        return new OrgUser(org.getId(), user.getId());
    }

    private Job persistJob(UUID orgId, UUID submittedBy, String sourceKey) {
        Job job = new Job(orgId, submittedBy, sourceKey);
        em.persist(job);
        em.flush();
        return job;
    }
}