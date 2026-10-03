package com.shamanth.taskforge.handler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;

import com.shamanth.taskforge.config.TaskForgeProperties;
import com.shamanth.taskforge.job.Job;
import com.shamanth.taskforge.job.JobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ReportJobHandlerTest {

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@TempDir
	Path dir;

	@Test
	void writesHeaderAndRowsWithRfc4180Escaping() throws Exception {
		JsonNode payload = json("""
				{"name": "sales", "columns": ["region", "note"],
				 "rows": [["north", 120], ["south, east", "say \\"hi\\""], ["west", null]]}
				""");

		handler().handle(job(7, payload));

		assertThat(Files.readString(dir.resolve("sales-7.csv")))
			.isEqualTo("region,note\r\nnorth,120\r\n\"south, east\",\"say \"\"hi\"\"\"\r\nwest,\r\n");
	}

	@Test
	void rerunOverwritesTheSameFileWithoutLeftovers() throws Exception {
		ReportJobHandler handler = handler();

		handler.handle(job(9, json("{\"name\": \"daily\", \"columns\": [\"region\", \"amount\"], \"rows\": [[\"a\", 1]]}")));
		handler.handle(job(9, json("{\"name\": \"daily\", \"columns\": [\"region\", \"amount\"], \"rows\": [[\"b\", 2]]}")));

		try (Stream<Path> files = Files.list(dir)) {
			assertThat(files.map((path) -> path.getFileName().toString())).containsExactly("daily-9.csv");
		}
		assertThat(Files.readString(dir.resolve("daily-9.csv"))).isEqualTo("region,amount\r\nb,2\r\n");
	}

	@Test
	void validateRejectsUnsafeNamesAndMissingColumns() {
		ReportJobHandler handler = handler();

		assertThatIllegalArgumentException()
			.isThrownBy(() -> handler.validate(json("{\"name\": \"../etc\", \"columns\": [\"a\"]}")));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> handler.validate(json("{\"name\": \" \", \"columns\": [\"a\"]}")));
		assertThatIllegalArgumentException().isThrownBy(() -> handler.validate(json("{\"name\": \"ok\"}")));
		assertThatCode(() -> handler.validate(json("{\"name\": \"ok\", \"columns\": [\"a\"]}")))
			.doesNotThrowAnyException();
	}

	private ReportJobHandler handler() {
		TaskForgeProperties.Report report = new TaskForgeProperties.Report(dir.toString());
		return new ReportJobHandler(new TaskForgeProperties(null, null, null, report, null));
	}

	private JsonNode json(String json) {
		return jsonMapper.readTree(json);
	}

	private static Job job(long id, JsonNode payload) {
		Instant now = Instant.now();
		return new Job(id, "REPORT", payload, JobStatus.RUNNING, 0, now, 1, 5, "test-worker", now.plusSeconds(30),
				null, null, now, now);
	}

}
