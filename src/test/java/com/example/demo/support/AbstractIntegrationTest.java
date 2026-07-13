package com.example.demo.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

import java.util.Set;

/**
 * Base for endpoint integration tests. Boots the full application against a
 * Testcontainers-managed MySQL (seeded with the same {@code init.sql} the compose
 * stack uses) and Redis, and cleans the tables and balance cache before each test
 * for isolation.
 *
 * <p>Prerequisite: a running Docker daemon. The compose stack is NOT required for
 * {@code ./mvnw verify}; it is only needed to run the app itself or the opt-in
 * {@link com.example.demo.RocketMqSmokeIT}.
 *
 * <p>The containers use the singleton pattern (static, started once per JVM) so
 * all IT classes share one MySQL and one Redis; Testcontainers' Ryuk sidecar
 * removes them when the JVM exits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "rocketmq.enabled=false")
public abstract class AbstractIntegrationTest {

    // Docker Engine 29+ rejects Docker API versions below 1.44 with HTTP 400, and
    // the docker-java bundled with Testcontainers 1.21.x still handshakes with
    // 1.32 by default. Pinning a supported version (respecting an external
    // override) restores the connection; drop this once the bundled docker-java
    // defaults to a version the engine accepts.
    static {
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", "1.44");
        }
    }

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("taskdb")
            .withUsername("taskuser")
            .withPassword("taskpass")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            // Same schema seed as the compose stack (repo-root init.sql); Maven and
            // IDEs both run tests with the project root as the working directory.
            .withCopyFileToContainer(
                    MountableFile.forHostPath("init.sql"),
                    "/docker-entrypoint-initdb.d/init.sql");

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeEach
    void cleanState() {
        jdbc.sql("DELETE FROM audit_log").update();
        // Reversal rows carry a self-FK (reversal_of → transfer.id), so delete the
        // children before the transfers they point at, then the accounts.
        jdbc.sql("DELETE FROM transfer WHERE reversal_of IS NOT NULL").update();
        jdbc.sql("DELETE FROM transfer").update();
        jdbc.sql("DELETE FROM account").update();
        Set<String> keys = redis.keys("balance:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
