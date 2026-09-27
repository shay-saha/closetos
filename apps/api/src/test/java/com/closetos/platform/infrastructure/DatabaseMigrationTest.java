package com.closetos.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
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
        assertThat(DatabaseMigration.migrate(environment).migrationsExecuted).isEqualTo(10);
        assertThat(DatabaseMigration.migrate(environment).migrationsExecuted).isZero();
        try (var connection = connect();
                var statement = connection.createStatement();
                var result =
                        statement.executeQuery(
                                "SELECT count(*) FROM flyway_schema_history WHERE success")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(10);
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
    void adoptsAlreadyRunningExecutionsAndRetainsTheirCapacityWhenTheImageIsDeleted()
            throws Exception {
        Flyway.configure()
                .dataSource(
                        environment.get("DATABASE_URL"),
                        environment.get("DATABASE_USERNAME"),
                        environment.get("DATABASE_PASSWORD"))
                .target("009")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (var connection = connect();
                var statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO user_profile(id,cognito_sub,display_name) VALUES ('00000000-0000-4000-8000-000000000001','migration-test','Migration test')");
            statement.execute(
                    "INSERT INTO wardrobe(id,owner_id,name) VALUES ('00000000-0000-4000-8000-000000000002','00000000-0000-4000-8000-000000000001','Wardrobe')");
            statement.execute(
                    "INSERT INTO garment(id,wardrobe_id,name,category,created_at,updated_at) VALUES ('00000000-0000-4000-8000-000000000003','00000000-0000-4000-8000-000000000002','Shirt','TOP',now(),now())");
            statement.execute(
                    """
                    INSERT INTO garment_image(id,garment_id,wardrobe_id,user_id,image_role,original_filename,mime_type,source_s3_key,expected_size,source_checksum,upload_key,processing_status)
                    VALUES ('00000000-0000-4000-8000-000000000004','00000000-0000-4000-8000-000000000003','00000000-0000-4000-8000-000000000002','00000000-0000-4000-8000-000000000001',
                        'FRONT','shirt.png','image/png','users/00000000-0000-4000-8000-000000000001/garments/00000000-0000-4000-8000-000000000003/images/00000000-0000-4000-8000-000000000004/original.png',
                        10,'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=','00000000-0000-4000-8000-000000000005','PROCESSING_MEDIA')
                    """);
            statement.execute(
                    """
                    INSERT INTO processing_job(id,image_id,pipeline_version,execution_arn,state,attempt_count)
                    VALUES ('00000000-0000-4000-8000-000000000006','00000000-0000-4000-8000-000000000004','1-r1',
                        'arn:aws:states:eu-west-2:123456789012:execution:closetos-dev-media:garment-image-00000000-0000-4000-8000-000000000004-pipeline-1-r1','PROCESSING_MEDIA',1)
                    """);
        }
        assertThat(DatabaseMigration.migrate(environment).migrationsExecuted).isEqualTo(1);
        try (var connection = connect();
                var statement = connection.createStatement();
                var result =
                        statement.executeQuery(
                                "SELECT state_machine_arn, execution_name, attempted, payload->>'jobId', payload->>'outputPrefix' FROM workflow_slot")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1))
                    .isEqualTo(
                            "arn:aws:states:eu-west-2:123456789012:stateMachine:closetos-dev-media");
            assertThat(result.getString(2))
                    .isEqualTo("garment-image-00000000-0000-4000-8000-000000000004-pipeline-1-r1");
            assertThat(result.getBoolean(3)).isTrue();
            assertThat(result.getString(4)).isEqualTo("00000000-0000-4000-8000-000000000006");
            assertThat(result.getString(5))
                    .endsWith("/images/00000000-0000-4000-8000-000000000004/pipelines/1-r1/");
            assertThat(result.next()).isFalse();
        }
        try (var connection = connect();
                var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM garment_image");
            try (var remaining = statement.executeQuery("SELECT count(*) FROM workflow_slot")) {
                assertThat(remaining.next()).isTrue();
                assertThat(remaining.getInt(1)).isEqualTo(1);
            }
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
