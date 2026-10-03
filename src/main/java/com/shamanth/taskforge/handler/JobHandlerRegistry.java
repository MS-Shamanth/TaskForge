package com.shamanth.taskforge.handler;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

@Component
public class JobHandlerRegistry {

	private final Map<String, JobHandler> handlers;

	public JobHandlerRegistry(List<JobHandler> handlers) {
		Map<String, JobHandler> byType = new HashMap<>();
		for (JobHandler handler : handlers) {
			JobHandler existing = byType.putIfAbsent(handler.type(), handler);
			if (existing != null) {
				throw new IllegalStateException("Duplicate JobHandler for type " + handler.type() + ": "
						+ existing.getClass().getName() + " and " + handler.getClass().getName());
			}
		}
		this.handlers = Map.copyOf(byType);
	}

	public Optional<JobHandler> find(String type) {
		return Optional.ofNullable(handlers.get(type));
	}

	public Set<String> types() {
		return Collections.unmodifiableSet(new TreeSet<>(handlers.keySet()));
	}

}
