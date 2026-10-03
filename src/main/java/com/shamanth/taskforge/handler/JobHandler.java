package com.shamanth.taskforge.handler;

import com.shamanth.taskforge.job.Job;
import tools.jackson.databind.JsonNode;

public interface JobHandler {

	String type();

	/** Called on submission; throw IllegalArgumentException to reject the payload (the API answers 400). */
	default void validate(JsonNode payload) {
	}

	void handle(Job job) throws Exception;

}
