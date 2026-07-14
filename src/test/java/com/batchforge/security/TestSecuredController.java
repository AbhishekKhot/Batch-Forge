package com.batchforge.security;

import com.batchforge.auth.AuthenticatedUser;
import com.batchforge.job.JobRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
class TestSecuredController {

    private final JobRepository jobRepository;

    TestSecuredController(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @GetMapping("/test/whoami")
    public Map<String, String> whoami(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of(
                "userId", user.userId().toString(),
                "orgId", user.orgId().toString(),
                "email", user.email(),
                "role", user.role().name());
    }

    @GetMapping("/test/admin-only")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> adminOnly(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("email", user.email(), "role", user.role().name());
    }

    @GetMapping("/test/my-jobs")
    public Map<String, Long> myJobs(@AuthenticationPrincipal AuthenticatedUser user) {
        long count = jobRepository.findByOrgId(user.orgId(), PageRequest.of(0, 100)).getTotalElements();
        return Map.of("count", count);
    }
}