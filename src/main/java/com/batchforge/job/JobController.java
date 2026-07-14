package com.batchforge.job;

import com.batchforge.auth.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateJobResponse createJob(@AuthenticationPrincipal AuthenticatedUser user) {
        return jobService.createImportJob(user.orgId(), user.userId());
    }

    @PostMapping("/{id}/uploaded")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobStatusResponse confirmUpload(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return jobService.confirmUpload(id, user.orgId());
    }
}