package com.shamanth.taskforge;

import org.springframework.boot.SpringApplication;

public class TestTaskForgeApplication {

	public static void main(String[] args) {
		SpringApplication.from(TaskForgeApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
