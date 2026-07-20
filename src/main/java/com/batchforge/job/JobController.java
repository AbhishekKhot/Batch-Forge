package com.batchforge.job;

import com.batchforge.auth.AuthenticatedUser;
import com.batchforge.common.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/jobs")
@RequiredArgsConstructor
@Tag(name = "Jobs", description = "Create, upload, monitor, and retrieve CSV import jobs")
public class JobController {

    private final JobService jobService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a CSV import job",
            description = "Creates a new CSV import job and returns the job id plus a presigned URL the client uses to upload the CSV directly to object storage.")
    @ApiResponse(responseCode = "201", description = "Job created; presigned upload URL returned")
    public CreateJobResponse createJob(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user) {
        return jobService.createImportJob(user.orgId(), user.userId());
    }

    @PostMapping("/{id}/uploaded")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Confirm upload and enqueue the job",
            description = "Signals that the CSV has been uploaded and enqueues the job for asynchronous processing.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Upload confirmed; job enqueued"),
            @ApiResponse(responseCode = "404", description = "Job not found (errorCode JOB_NOT_FOUND)")
    })
    public JobStatusResponse confirmUpload(@PathVariable UUID id, @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user) {
        return jobService.confirmUpload(id, user.orgId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get job status and progress",
            description = "Returns the current status, row totals, and processed/failed counts for a job.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Job found"),
            @ApiResponse(responseCode = "404", description = "Job not found (errorCode JOB_NOT_FOUND)")
    })
    public JobResponse getJob(@PathVariable UUID id, @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user) {
        return jobService.getJob(id, user.orgId());
    }

    @GetMapping("/{id}/result")
    @Operation(summary = "Get the result of a completed job",
            description = "Returns the result summary for a job, including success/failure counts and a link to the error report when rows failed validation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Result available"),
            @ApiResponse(responseCode = "404", description = "Job not found (errorCode JOB_NOT_FOUND)")
    })
    public ResultResponse getResult(@PathVariable UUID id, @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user) {
        return jobService.getResult(id, user.orgId());
    }

    @GetMapping
    @Operation(summary = "List jobs",
            description = "Returns a paginated list of the caller's organization's jobs, most recent first, optionally filtered by status.")
    @ApiResponse(responseCode = "200", description = "Page of jobs returned")
    public PagedResponse<JobResponse> listJobs(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Optional status filter") @RequestParam(required = false) JobStatus status) {
        return jobService.listJobs(user.orgId(), page, size, status);
    }
}