package com.shamanth.taskforge;

import java.time.Duration;

import com.shamanth.taskforge.job.Job;
import com.shamanth.taskforge.job.JobRepository;
import com.shamanth.taskforge.job.JobStatus;
import com.shamanth.taskforge.support.TestHandlersConfig;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.awaitility.Awaitility.await;

/**
 * "Live" context: the application's own poller and lease reaper run with the fast settings from
 * application-test.properties. Has its own PostgreSQL container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, TestHandlersConfig.class })
public abstract class AbstractIntegrationTest {

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	protected JobRepository jobRepository;

	@Autowired
	protected JsonMapper jsonMapper;

	protected Job awaitStatus(long id, JobStatus expected, Duration timeout) {
		return await().atMost(timeout)
			.pollInterval(Duration.ofMillis(25))
			.until(() -> jobRepository.findById(id).orElseThrow(), (job) -> job.status() == expected);
	}

}
