package com.batchforge.auth;

import com.batchforge.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BatchForgeUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .map(user -> new BatchForgeUserDetails(
                        user.getId(),
                        user.getOrgId(),
                        user.getEmail(),
                        user.getPasswordHash(),
                        user.getRole()))
                .orElseThrow(() -> new UsernameNotFoundException("No user for email: " + email));
    }
}