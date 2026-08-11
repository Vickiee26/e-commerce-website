package com.mvp.ecommercebackend.config;

import com.mvp.ecommercebackend.auth.TokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The stateless security configuration.
 *
 * <p>CSRF is disabled because credentials are carried in an {@code Authorization} header rather than
 * a cookie, so there is nothing a third-party site could cause the browser to send automatically.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Strength 10 as specified; the default may change between Spring Security versions. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public ProblemResponseWriter problemResponseWriter(ObjectMapper objectMapper) {
        return new ProblemResponseWriter(objectMapper);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    TokenService tokenService,
                                                    ProblemResponseWriter problemResponseWriter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Exact paths, not /auth/**: logout must fall through to authenticated.
                        .requestMatchers(HttpMethod.POST,
                                "/auth/register",
                                "/auth/login",
                                "/auth/refresh",
                                "/auth/password-reset/request",
                                "/auth/password-reset/confirm").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products/**", "/api/categories/**")
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/swagger-ui", "/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problemResponseWriter)
                        .accessDeniedHandler(problemResponseWriter))
                .addFilterBefore(new JwtAuthenticationFilter(tokenService, problemResponseWriter),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
