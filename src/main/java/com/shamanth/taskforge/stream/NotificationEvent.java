package com.shamanth.taskforge.stream;

import java.time.Instant;

public record NotificationEvent(long jobId, String message, Instant timestamp) {
}
