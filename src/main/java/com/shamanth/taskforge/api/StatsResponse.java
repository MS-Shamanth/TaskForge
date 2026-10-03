package com.shamanth.taskforge.api;

import java.util.Map;

import com.shamanth.taskforge.job.JobStatus;

public record StatsResponse(Map<JobStatus, Long> counts, long total) {
}
