package com.geli.warehouse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class PostgresIntegrationTest {

    protected static final String SCHEMA = "warehouse_test_" + UUID.randomUUID().toString().replace("-", "");

    private static PostgreSQLContainer<?> postgres;
    private static String databaseUrl;
    private static String databaseUsername;
    private static String databasePassword;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        if (databaseUrl == null) {
            databaseUrl = System.getProperty("test.database.url");
            if (databaseUrl == null || databaseUrl.isBlank()) {
                postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                        .withDatabaseName("warehouse_test")
                        .withUsername("warehouse_test")
                        .withPassword("warehouse_test");
                postgres.start();
                databaseUrl = postgres.getJdbcUrl();
                databaseUsername = postgres.getUsername();
                databasePassword = postgres.getPassword();
            } else {
                databaseUsername = System.getProperty("test.database.username", "postgres");
                databasePassword = System.getProperty("test.database.password", "");
            }
        }
        registry.add("spring.datasource.url", () -> databaseUrl
                + (databaseUrl.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", () -> databaseUsername);
        registry.add("spring.datasource.password", () -> databasePassword);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("TRUNCATE TABLE " + SCHEMA + ".sales, " + SCHEMA + ".variants, "
                + SCHEMA + ".items RESTART IDENTITY CASCADE");
    }

    @AfterAll
    static void dropTestSchema() throws Exception {
        if (databaseUrl != null) {
            try (Connection connection = DriverManager.getConnection(databaseUrl, databaseUsername, databasePassword);
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            }
        }
    }
}
