package com.shamanth.taskforge.stream;

import java.util.List;

import com.shamanth.taskforge.job.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class WebSocketPublisher {

	public static final String JOBS_TOPIC = "/topic/jobs";

	public static final String NOTIFICATIONS_TOPIC = "/topic/notifications";

	private static final Logger log = LoggerFactory.getLogger(WebSocketPublisher.class);

	private final SimpMessagingTemplate messagingTemplate;

	public WebSocketPublisher(SimpMessagingTemplate messagingTemplate) {
		this.messagingTemplate = messagingTemplate;
	}

	public void publishStatus(Job job) {
		try {
			messagingTemplate.convertAndSend(JOBS_TOPIC, JobStatusEvent.from(job));
		}
		catch (RuntimeException ex) {
			log.warn("Could not publish status {} of job {}: {}", job.status(), job.id(), ex.getMessage());
		}
	}

	public void publishStatus(List<Job> jobs) {
		jobs.forEach(this::publishStatus);
	}

	public void publishNotification(NotificationEvent event) {
		messagingTemplate.convertAndSend(NOTIFICATIONS_TOPIC, event);
	}

}
