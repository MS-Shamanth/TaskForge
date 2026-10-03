package com.shamanth.taskforge.api;

import java.net.URI;

import com.shamanth.taskforge.job.JobPage;
import com.shamanth.taskforge.job.JobService;
import com.shamanth.taskforge.job.JobStatus;
import com.shamanth.taskforge.job.NewJob;
import com.shamanth.taskforge.job.SubmitResult;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

	private final JobService jobService;

	public JobController(JobService jobService) {
		this.jobService = jobService;
	}

	@PostMapping
	public ResponseEntity<JobResponse> submit(@Valid @RequestBody SubmitJobRequest request,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		SubmitResult result = jobService.submit(new NewJob(request.type(), request.payload(), request.priority(),
				request.runAt(), request.maxAttempts(), idempotencyKey));
		JobResponse body = JobResponse.from(result.job());
		if (!result.created()) {
			return ResponseEntity.ok(body);
		}
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
			.path("/{id}")
			.buildAndExpand(body.id())
			.toUri();
		return ResponseEntity.created(location).body(body);
	}

	@GetMapping("/{id}")
	public JobResponse get(@PathVariable long id) {
		return JobResponse.from(jobService.get(id));
	}

	@GetMapping
	public PageResponse<JobResponse> list(@RequestParam(required = false) JobStatus status,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		JobPage result = jobService.list(status, page, size);
		int totalPages = (int) ((result.totalElements() + result.size() - 1) / result.size());
		return new PageResponse<>(result.items().stream().map(JobResponse::from).toList(), result.page(),
				result.size(), result.totalElements(), totalPages);
	}

	@PostMapping("/{id}/retry")
	public JobResponse retry(@PathVariable long id) {
		return JobResponse.from(jobService.retry(id));
	}

	@DeleteMapping("/{id}")
	public JobResponse cancel(@PathVariable long id) {
		return JobResponse.from(jobService.cancel(id));
	}

}
