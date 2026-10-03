package com.shamanth.taskforge;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TaskForgeApplication {

	public static void main(String[] args) {
		// PgJDBC sends the JVM time zone to the server, and current postgres images reject legacy IDs such as Asia/Calcutta.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(TaskForgeApplication.class, args);
	}

}
