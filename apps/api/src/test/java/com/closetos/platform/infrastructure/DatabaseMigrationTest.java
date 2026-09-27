package com.closetos.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class DatabaseMigrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg18")
                            .asCompatibleSubstituteFor("postgres"));

    private Map<String, String> environment;

    @BeforeEach
    void isolatedDatabase() throws Exception {
        String database = "migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        environment =
                Map.of(
                        "DATABASE_URL",
                                "jdbc:postgresql://"
                                        + POSTGRES.getHost()
                                        + ":"
                                        + POSTGRES.getMappedPort(5432)
                                        + "/"
                                        + database,
                        "DATABASE_USERNAME", POSTGRES.getUsername(),
                        "DATABASE_PASSWORD", POSTGRES.getPassword());
    }

    @Test
    void migratesAnEmptyDatabaseAndSafelyReplaysWithoutStartingSpring() throws Exception {
        assertThat(DatabaseMigration.migrate(environment).migrationsExecuted).isEqualTo(9);
        assertThat(DatabaseMigration.migrate(environment).migrationsExecuted).isZero();
        try (var connection = connect();
                var statement = connection.createStatement();
                var result =
                        statement.executeQuery(
                                "SELECT count(*) FROM flyway_schema_history WHERE success")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(9);
        }
        try (var connection = connect();
                var statement = connection.createStatement();
                var result =
                        statement.executeQuery(
                                "SELECT extversion FROM pg_extension WHERE extname='vector'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isNotBlank();
        }
    }

    @Test
    void refusesToContinueWithChangedAppliedMigrationChecksums() throws Exception {
        DatabaseMigration.migrate(environment);
        try (var connection = connect();
                var statement = connection.createStatement()) {
            assertThat(
                            statement.executeUpdate(
                                    "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version='001'"))
                    .isEqualTo(1);
        }
        assertThatThrownBy(() -> DatabaseMigration.migrate(environment))
                .isInstanceOf(FlywayValidateException.class);
    }

    @Test
    void refusesImplicitOrIncompleteDatabaseConfigurationWithoutExposingSecrets() {
        for (String name : environment.keySet()) {
            var missing = new HashMap<>(environment);
            missing.remove(name);
            assertThatThrownBy(() -> DatabaseMigration.migrate(missing))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Migration requires " + name + ".");
        }
        var invalid = new HashMap<>(environment);
        invalid.put("DATABASE_URL", "jdbc:h2:mem:unexpected");
        assertThatThrownBy(() -> DatabaseMigration.migrate(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PostgreSQL JDBC URL");
        assertThatThrownBy(() -> DatabaseMigration.main(new String[] {"--clean"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private java.sql.Connection connect() throws Exception {
        return DriverManager.getConnection(
                environment.get("DATABASE_URL"),
                environment.get("DATABASE_USERNAME"),
                environment.get("DATABASE_PASSWORD"));
    }
}
