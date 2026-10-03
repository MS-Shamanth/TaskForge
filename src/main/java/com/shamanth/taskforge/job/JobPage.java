package com.shamanth.taskforge.job;

import java.util.List;

public record JobPage(List<Job> items, int page, int size, long totalElements) {
}
