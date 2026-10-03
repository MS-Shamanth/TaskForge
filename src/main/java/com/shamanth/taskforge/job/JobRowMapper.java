package com.shamanth.taskforge.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.RowMapper;

class JobRowMapper implements RowMapper<Job> {

	private final JsonMapper jsonMapper;

	JobRowMapper(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public Job mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new Job(
				rs.getLong("id"),
				rs.getString("type"),
				jsonMapper.readTree(rs.getString("payload")),
				JobStatus.valueOf(rs.getString("status")),
				rs.getInt("priority"),
				instant(rs, "run_at"),
				rs.getInt("attempts"),
				rs.getInt("max_attempts"),
				rs.getString("locked_by"),
				instant(rs, "locked_until"),
				rs.getString("idempotency_key"),
				rs.getString("last_error"),
				instant(rs, "created_at"),
				instant(rs, "updated_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return (value != null) ? value.toInstant() : null;
	}

}
