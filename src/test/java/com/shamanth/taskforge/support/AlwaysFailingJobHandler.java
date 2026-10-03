package com.shamanth.taskforge.support;

import com.shamanth.taskforge.handler.JobHandler;
import com.shamanth.taskforge.job.Job;

public class AlwaysFailingJobHandler implements JobHandler {

	@Override
	public String type() {
		return "ALWAYS_FAIL";
	}

	@Override
	public void handle(Job job) {
		throw new IllegalStateException("boom: this job always fails");
	}

}
