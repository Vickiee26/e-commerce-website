package com.mvp.ecommercebackend.config;

import com.mvp.ecommercebackend.common.RequestContext;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Throttles the three auth endpoints that an attacker would otherwise hammer.
 *
 * <p>Placed ahead of authentication so a flood is rejected before it costs a database read or a
 * BCrypt verification — the expensive work is exactly what makes those endpoints worth flooding.
 *
 * <p>Keyed by client IP <em>and</em> path, so exhausting the login budget does not also block
 * registration for that address.
 *
 * <p>Known limitation, recorded in the design doc: the buckets are in-process, so limits are
 * per-instance, reset on restart, and the map grows with the number of distinct source addresses.
 * Moving the state to Redis is deferred to the ops slice.
 *
 * <p>Not a {@code @Component} or {@code @Bean}: Spring Boot registers {@code Filter} beans in the
 * main servlet chain as well, which would double-count every request. {@link SecurityConfig}
 * constructs it.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_PATHS = Set.of(
            "/auth/login",
            "/auth/register",
            "/auth/password-reset/request");

    private final RateLimitProperties properties;
    private final ProblemResponseWriter problemResponseWriter;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties,
                           ProblemResponseWriter problemResponseWriter) {
        this.properties = properties;
        this.problemResponseWriter = problemResponseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled()
                || !HttpMethod.POST.matches(request.getMethod())
                || !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        // Same X-Forwarded-For handling as the audit trail, so the two always agree on the client.
        String clientIp = RequestContext.from(request).ipAddress();
        String key = clientIp + "|" + request.getRequestURI();

        Bucket bucket = buckets.computeIfAbsent(key, ignored -> newBucket());
        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
            return;
        }

        problemResponseWriter.writeTooManyRequests(request, response,
                properties.refillPeriod().toSeconds());
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(properties.capacity())
                .refillGreedy(properties.capacity(), properties.refillPeriod())
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
