package com.shamanth.taskforge.api;

import java.util.Map;

import com.shamanth.taskforge.job.JobService;
import com.shamanth.taskforge.job.JobStatus;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatsController {

	private final JobService jobService;

	public StatsController(JobService jobService) {
		this.jobService = jobService;
	}

	@GetMapping("/api/stats")
	public StatsResponse stats() {
		Map<JobStatus, Long> counts = jobService.stats();
		long total = counts.values().stream().mapToLong(Long::longValue).sum();
		return new StatsResponse(counts, total);
	}

}
