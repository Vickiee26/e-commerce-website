package com.mvp.ecommercebackend.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Base class for every integration test.
 *
 * <p>The container is static, so a single Postgres instance is shared by all subclasses for the
 * whole suite. Isolation comes from truncating tables between tests instead of restarting the
 * database, which keeps the suite fast.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    /**
     * Testcontainers 2.x: the class lives in org.testcontainers.postgresql and is NOT generic.
     * Spring Boot's JdbcContainerConnectionDetailsFactory binds it via @ServiceConnection because
     * it still extends org.testcontainers.containers.JdbcDatabaseContainer.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    protected MockMvc mockMvc;

    /**
     * Boot 4 auto-configures Jackson 3 (tools.jackson), registering a JsonMapper bean. Jackson 2
     * (com.fasterxml.jackson) is still on the classpath transitively but has no bean, so injecting
     * the Jackson 2 ObjectMapper fails with NoSuchBeanDefinitionException.
     */
    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * Wipes application data while preserving Flyway's history and the roles seeded by V2.
     */
    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE auth_events,
                               password_reset_tokens,
                               refresh_tokens,
                               addresses,
                               user_roles,
                               users,
                               product_resources,
                               product_variants,
                               products,
                               category_types,
                               categories
                RESTART IDENTITY CASCADE
                """);
    }
}
