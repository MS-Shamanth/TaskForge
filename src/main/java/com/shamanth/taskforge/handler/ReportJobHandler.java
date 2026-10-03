package com.shamanth.taskforge.handler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

import com.shamanth.taskforge.config.TaskForgeProperties;
import com.shamanth.taskforge.job.Job;
import tools.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

@Component
public class ReportJobHandler implements JobHandler {

	private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

	private static final String CRLF = "\r\n";

	private final Path dir;

	public ReportJobHandler(TaskForgeProperties properties) {
		this.dir = Path.of(properties.report().dir());
	}

	@Override
	public String type() {
		return "REPORT";
	}

	@Override
	public void validate(JsonNode payload) {
		JsonNode name = payload.get("name");
		if (name == null || !name.isString() || !NAME.matcher(name.asString()).matches()) {
			throw new IllegalArgumentException("payload.name must be 1-64 characters of A-Z, a-z, 0-9, '_' or '-'");
		}
		JsonNode columns = payload.get("columns");
		if (columns == null || !columns.isArray() || columns.isEmpty()) {
			throw new IllegalArgumentException("payload.columns must be a non-empty array of strings");
		}
		for (JsonNode column : columns) {
			if (!column.isString()) {
				throw new IllegalArgumentException("payload.columns must be a non-empty array of strings");
			}
		}
		JsonNode rows = payload.get("rows");
		if (rows != null && !rows.isNull()) {
			if (!rows.isArray()) {
				throw new IllegalArgumentException("payload.rows must be an array of arrays");
			}
			for (JsonNode row : rows) {
				if (!row.isArray()) {
					throw new IllegalArgumentException("payload.rows must be an array of arrays");
				}
			}
		}
	}

	@Override
	public void handle(Job job) throws IOException {
		JsonNode payload = job.payload();
		validate(payload);
		String fileName = payload.get("name").asString() + "-" + job.id() + ".csv";
		Files.createDirectories(dir);
		// Temp file + atomic move: a rerun replaces the report and readers never see a partial file.
		Path tmp = Files.createTempFile(dir, fileName + "-", ".tmp");
		try {
			Files.writeString(tmp, toCsv(payload), StandardCharsets.UTF_8);
			Files.move(tmp, dir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		}
		finally {
			Files.deleteIfExists(tmp);
		}
	}

	private static String toCsv(JsonNode payload) {
		StringBuilder csv = new StringBuilder();
		appendRow(csv, payload.get("columns"));
		JsonNode rows = payload.get("rows");
		if (rows != null && !rows.isNull()) {
			for (JsonNode row : rows) {
				appendRow(csv, row);
			}
		}
		return csv.toString();
	}

	private static void appendRow(StringBuilder csv, JsonNode cells) {
		boolean first = true;
		for (JsonNode cell : cells) {
			if (!first) {
				csv.append(',');
			}
			csv.append(escape(render(cell)));
			first = false;
		}
		csv.append(CRLF);
	}

	private static String render(JsonNode cell) {
		if (cell == null || cell.isNull() || cell.isMissingNode()) {
			return "";
		}
		return cell.isValueNode() ? cell.asString() : cell.toString();
	}

	private static String escape(String value) {
		if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) {
			return value;
		}
		return '"' + value.replace("\"", "\"\"") + '"';
	}

}
