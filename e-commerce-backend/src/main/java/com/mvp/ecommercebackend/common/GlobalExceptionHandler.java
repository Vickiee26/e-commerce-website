package com.mvp.ecommercebackend.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * The single place that turns an exception into a response body.
 *
 * <p>Everything leaves as RFC 7807 {@code application/problem+json}. Spring selects that content
 * type automatically for a {@link ProblemDetail} return value.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** One invalid field. A record rather than a Map, so the JSON key order is stable. */
    public record FieldErrorDetail(String field, String message) {
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidationFailure(MethodArgumentNotValidException exception,
                                          HttpServletRequest request) {
        List<FieldErrorDetail> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorDetail(
                        error.getField(),
                        error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()))
                .toList();

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "Request contains invalid fields", request);
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException exception,
                                            HttpServletRequest request) {
        List<FieldErrorDetail> errors = exception.getConstraintViolations().stream()
                .map(violation -> new FieldErrorDetail(
                        violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "Request contains invalid fields", request);
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Unparseable body, or a path variable that is not the declared type. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail handleMalformedRequest(Exception exception, HttpServletRequest request) {
        log.debug("Rejected malformed request to {}", request.getRequestURI(), exception);
        return problem(HttpStatus.BAD_REQUEST, "Malformed request",
                "Request could not be read", request);
    }

    @ExceptionHandler({InvalidCredentialsException.class, InvalidTokenException.class})
    ProblemDetail handleUnauthorized(RuntimeException exception, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", exception.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden",
                "You do not have permission to perform this action", request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not found", exception.getMessage(), request);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    ProblemDetail handleDuplicate(DuplicateResourceException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflict", exception.getMessage(), request);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ProblemDetail handleRateLimited(RateLimitExceededException exception,
                                     HttpServletRequest request) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                exception.getMessage(), request);
    }

    /**
     * Last resort. The client gets a correlation id and nothing else; the cause is logged.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        String correlationId = UUID.randomUUID().toString();
        log.error("Unhandled exception [{}] while handling {} {}",
                correlationId, request.getMethod(), request.getRequestURI(), exception);

        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "An unexpected error occurred. Reference: " + correlationId, request);
        problem.setProperty("correlationId", correlationId);
        return problem;
    }

    private ProblemDetail problem(HttpStatusCode status, String title, String detail,
                                   HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
