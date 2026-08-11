package com.mvp.ecommercebackend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;

/**
 * Renders the two failures Spring Security raises inside the filter chain — unauthenticated and
 * insufficient role — in the same RFC 7807 shape as every controller error.
 *
 * <p>These never reach {@code GlobalExceptionHandler}, because no handler method runs.
 */
public class ProblemResponseWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ProblemResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                "Authentication is required to access this resource");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                        AccessDeniedException accessDeniedException) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "Forbidden",
                "You do not have permission to perform this action");
    }

    /** Also used directly by {@link JwtAuthenticationFilter}, which rejects before the chain. */
    public void writeUnauthorized(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                "Access token is not valid");
    }

    /** Used by {@link RateLimitFilter}, which also rejects before any controller runs. */
    public void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response,
                                      long retryAfterSeconds) throws IOException {
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        write(request, response, HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                "Too many requests. Try again in " + retryAfterSeconds + " seconds.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                        HttpStatus status, String title, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
