package com.shamanth.taskforge.handler;

import java.net.URI;
import java.net.URISyntaxException;

import com.shamanth.taskforge.config.TaskForgeProperties;
import com.shamanth.taskforge.job.Job;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WebhookJobHandler implements JobHandler {

	private final RestClient restClient;

	private final JsonMapper jsonMapper;

	public WebhookJobHandler(TaskForgeProperties properties, JsonMapper jsonMapper) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.webhook().connectTimeout());
		requestFactory.setReadTimeout(properties.webhook().readTimeout());
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
		this.jsonMapper = jsonMapper;
	}

	@Override
	public String type() {
		return "WEBHOOK";
	}

	@Override
	public void validate(JsonNode payload) {
		parseUrl(payload.get("url"));
	}

	@Override
	public void handle(Job job) {
		URI url = parseUrl(job.payload().get("url"));
		JsonNode body = job.payload().get("body");
		String json = (body == null || body.isNull()) ? "{}" : jsonMapper.writeValueAsString(body);
		restClient.post()
			.uri(url)
			.contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", "taskforge-job-" + job.id())
			.body(json)
			.retrieve()
			.onStatus((status) -> !status.is2xxSuccessful(), (request, response) -> {
				throw new WebhookDeliveryException("Webhook " + url + " responded " + response.getStatusCode().value());
			})
			.toBodilessEntity();
	}

	private static URI parseUrl(JsonNode url) {
		if (url == null || !url.isString()) {
			throw new IllegalArgumentException("payload.url must be an absolute http or https URL");
		}
		try {
			URI uri = new URI(url.asString());
			String scheme = uri.getScheme();
			if (!uri.isAbsolute() || uri.getHost() == null
					|| !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
				throw new IllegalArgumentException("payload.url must be an absolute http or https URL");
			}
			return uri;
		}
		catch (URISyntaxException ex) {
			throw new IllegalArgumentException("payload.url must be an absolute http or https URL");
		}
	}

}
