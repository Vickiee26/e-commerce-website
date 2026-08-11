package com.mvp.ecommercebackend.config;

import com.mvp.ecommercebackend.auth.AuthenticatedUser;
import com.mvp.ecommercebackend.auth.TokenService;
import com.mvp.ecommercebackend.common.InvalidTokenException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Populates the security context from a verified bearer token.
 *
 * <p>Not a {@code @Component}: Spring Boot would also register it in the main servlet chain, so it
 * would run twice per request. {@link SecurityConfig} constructs it instead.
 *
 * <p>A present-but-invalid {@code Authorization} header ends the request with 401 rather than
 * continuing anonymously, on any path. Continuing would report an expired token as a 404 or an
 * anonymous 200, which is much harder for a client to diagnose.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;
    private final ProblemResponseWriter problemResponseWriter;

    public JwtAuthenticationFilter(TokenService tokenService,
                                    ProblemResponseWriter problemResponseWriter) {
        this.tokenService = tokenService;
        this.problemResponseWriter = problemResponseWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!header.startsWith(BEARER_PREFIX)) {
            SecurityContextHolder.clearContext();
            problemResponseWriter.writeUnauthorized(request, response);
            return;
        }

        try {
            AuthenticatedUser principal =
                    tokenService.parseAccessToken(header.substring(BEARER_PREFIX.length()).trim());
            List<SimpleGrantedAuthority> authorities = principal.roles().stream()
                    // Spring Security's hasRole and @PreAuthorize expect the ROLE_ prefix.
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
            SecurityContextHolder.getContext().setAuthentication(
                    new PreAuthenticatedAuthenticationToken(principal, header, authorities));
        } catch (InvalidTokenException exception) {
            SecurityContextHolder.clearContext();
            problemResponseWriter.writeUnauthorized(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
