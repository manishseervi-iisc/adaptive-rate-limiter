package com.darl.ratelimiter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the Spring context loads cleanly with a real PostgreSQL instance
 * (via Testcontainers) and a mocked Redis.
 *
 * Day 1 goal: context loads, Flyway runs migrations, JPA validates schema.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class RateLimiterApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("rate_limiter_test")
                    .withUsername("darl")
                    .withPassword("darl_secret");

    @Test
    void contextLoads() {
        // If the context starts, Flyway ran, JPA validated, Postgres connected.
    }
}
