package com.example.demo.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Base for endpoint integration tests. Boots the full application against the real
 * MySQL from the project's {@code docker compose} stack (localhost:3306) and cleans
 * the tables before each test for isolation.
 *
 * <p>Prerequisite: {@code docker compose up -d} must be running.
 *
 * <p>NOTE: A Testcontainers-managed MySQL was the intended provider, but this
 * environment's Docker Engine (29.x) is not compatible with the docker-java client
 * bundled with the current Testcontainers version (the API handshake returns HTTP
 * 400). Once that is resolved, swap this base to a {@code @Testcontainers} MySQL
 * container seeded with {@code init.sql} — the tests themselves need no changes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("DELETE FROM transfer").update();
        jdbc.sql("DELETE FROM account").update();
    }
}
