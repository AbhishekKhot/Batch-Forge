package com.batchforge.auth;

import com.batchforge.user.Role;
import com.batchforge.user.User;
import com.batchforge.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchForgeUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private BatchForgeUserDetailsService service;

    @Test
    void loadsUserAndMapsToUserDetails() {
        UUID orgId = UUID.randomUUID();
        User user = new User("Owner@Example.com", "hashed-pw", orgId, Role.ORG_OWNER);
        when(userRepository.findByEmail("Owner@Example.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("Owner@Example.com");

        assertThat(details).isInstanceOf(BatchForgeUserDetails.class);
        assertThat(details.getUsername()).isEqualTo("Owner@Example.com");
        assertThat(details.getPassword()).isEqualTo("hashed-pw");
        assertThat(details.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_ORG_OWNER");

        BatchForgeUserDetails bf = (BatchForgeUserDetails) details;
        assertThat(bf.getOrgId()).isEqualTo(orgId);
        assertThat(bf.getRole()).isEqualTo(Role.ORG_OWNER);
    }

    @Test
    void throwsWhenEmailNotFound() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("ghost@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}