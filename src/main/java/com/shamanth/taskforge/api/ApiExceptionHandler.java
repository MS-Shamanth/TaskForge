package com.shamanth.taskforge.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.shamanth.taskforge.job.InvalidJobRequestException;
import com.shamanth.taskforge.job.JobConflictException;
import com.shamanth.taskforge.job.JobNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(JobNotFoundException.class)
	ResponseEntity<ApiError> notFound(JobNotFoundException ex, HttpServletRequest request) {
		return error(HttpStatus.NOT_FOUND, ex.getMessage(), request, Map.of());
	}

	@ExceptionHandler(JobConflictException.class)
	ResponseEntity<ApiError> conflict(JobConflictException ex, HttpServletRequest request) {
		return error(HttpStatus.CONFLICT, ex.getMessage(), request, Map.of());
	}

	@ExceptionHandler(InvalidJobRequestException.class)
	ResponseEntity<ApiError> invalidRequest(InvalidJobRequestException ex, HttpServletRequest request) {
		return error(HttpStatus.BAD_REQUEST, ex.getMessage(), request, Map.of());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> validationFailed(MethodArgumentNotValidException ex, HttpServletRequest request) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
			fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
		}
		return error(HttpStatus.BAD_REQUEST, "Validation failed", request, fieldErrors);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiError> unreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
		return error(HttpStatus.BAD_REQUEST, "Malformed JSON request body", request, Map.of());
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
		String message = "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'";
		return error(HttpStatus.BAD_REQUEST, message, request, Map.of());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
		if (ex instanceof ErrorResponse errorResponse) {
			String detail = errorResponse.getBody().getDetail();
			HttpStatusCode status = errorResponse.getStatusCode();
			return ResponseEntity.status(status)
				.headers(errorResponse.getHeaders())
				.body(body(status, (detail != null) ? detail : reason(status), request, Map.of()));
		}
		log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request, Map.of());
	}

	private static ResponseEntity<ApiError> error(HttpStatus status, String message, HttpServletRequest request,
			Map<String, String> fieldErrors) {
		return ResponseEntity.status(status).body(body(status, message, request, fieldErrors));
	}

	private static ApiError body(HttpStatusCode status, String message, HttpServletRequest request,
			Map<String, String> fieldErrors) {
		return new ApiError(Instant.now(), status.value(), reason(status), message, request.getRequestURI(),
				fieldErrors);
	}

	private static String reason(HttpStatusCode status) {
		HttpStatus known = HttpStatus.resolve(status.value());
		return (known != null) ? known.getReasonPhrase() : String.valueOf(status.value());
	}

}
