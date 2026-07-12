package com.batchforge.user;

import com.batchforge.organization.Organization;
import com.batchforge.support.PostgresContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresContainerConfiguration.class)
class UserRepositoryTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private UserRepository userRepository;

    @Test
    void findsUserByEmailIgnoringCase() {
        UUID orgId = persistOrganization("Acme");
        persistUser("Owner@Example.com", orgId, Role.ORG_OWNER);

        Optional<User> exact = userRepository.findByEmail("Owner@Example.com");
        Optional<User> lower = userRepository.findByEmail("owner@example.com");
        Optional<User> upper = userRepository.findByEmail("OWNER@EXAMPLE.COM");

        assertThat(exact).isPresent();
        assertThat(lower).isPresent();
        assertThat(upper).isPresent();
        assertThat(exact.get().getId()).isEqualTo(lower.get().getId());
        assertThat(lower.get().getId()).isEqualTo(upper.get().getId());
        assertThat(lower.get().getEmail()).isEqualTo("Owner@Example.com");
    }

    @Test
    void returnsEmptyWhenEmailNotFound() {
        assertThat(userRepository.findByEmail("nobody@example.com")).isEmpty();
    }

    private UUID persistOrganization(String name) {
        Organization org = new Organization(name);
        em.persist(org);
        em.flush();
        return org.getId();
    }

    private void persistUser(String email, UUID orgId, Role role) {
        em.persist(new User(email, "hash", orgId, role));
        em.flush();
        em.clear();
    }
}