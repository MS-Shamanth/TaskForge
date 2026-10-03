package com.shamanth.taskforge.handler;

import java.time.Instant;

import com.shamanth.taskforge.job.Job;
import com.shamanth.taskforge.stream.NotificationEvent;
import com.shamanth.taskforge.stream.WebSocketPublisher;
import tools.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

@Component
public class NotificationJobHandler implements JobHandler {

	private static final int MAX_MESSAGE_LENGTH = 1000;

	private final WebSocketPublisher publisher;

	public NotificationJobHandler(WebSocketPublisher publisher) {
		this.publisher = publisher;
	}

	@Override
	public String type() {
		return "NOTIFICATION";
	}

	@Override
	public void validate(JsonNode payload) {
		JsonNode message = payload.get("message");
		if (message == null || !message.isString() || message.asString().isBlank()
				|| message.asString().length() > MAX_MESSAGE_LENGTH) {
			throw new IllegalArgumentException(
					"payload.message must be a non-blank string of at most " + MAX_MESSAGE_LENGTH + " characters");
		}
	}

	@Override
	public void handle(Job job) {
		validate(job.payload());
		String message = job.payload().get("message").asString();
		publisher.publishNotification(new NotificationEvent(job.id(), message, Instant.now()));
	}

}
