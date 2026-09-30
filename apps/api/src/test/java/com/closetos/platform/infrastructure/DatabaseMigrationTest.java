package com.closetos.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
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
        assertThat(DatabaseMigration.migrate(environment).migrationsExecuted).isEqualTo(14);
        assertThat(DatabaseMigration.migrate(environment).migrationsExecuted).isZero();
        try (var connection = connect();
                var statement = connection.createStatement();
                var result =
                        statement.executeQuery(
                                "SELECT count(*) FROM flyway_schema_history WHERE success")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(14);
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
        assertThat(DatabaseMigration.migrate(environment).migrationsExecuted).isEqualTo(5);
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
    void provisionsRuntimeCredentialsAndRestrictsSchemaAndAdministrativeData() throws Exception {
        var configured = new HashMap<>(environment);
        String password = "Runtime 'password' with spaces; " + UUID.randomUUID();
        configured.put("APPLICATION_DATABASE_PASSWORD", password);
        DatabaseMigration.migrate(configured);
        try (var connection = connectRuntime(password);
                var statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO user_profile(id,cognito_sub,display_name) VALUES ('00000000-0000-4000-8000-000000000001','restricted-test','Runtime')");
            statement.execute(
                    "INSERT INTO wardrobe(id,owner_id,name) VALUES ('00000000-0000-4000-8000-000000000002','00000000-0000-4000-8000-000000000001','Wardrobe')");
            statement.execute(
                    "INSERT INTO garment(id,wardrobe_id,name,category,created_at,updated_at) VALUES ('00000000-0000-4000-8000-000000000003','00000000-0000-4000-8000-000000000002','Shirt','TOP',now(),now())");
            statement.execute("UPDATE garment SET name='Updated shirt'");
            try (var result = statement.executeQuery("SELECT name FROM garment")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("Updated shirt");
            }
            statement.execute("SELECT maximum_active FROM workflow_capacity WHERE id=1 FOR UPDATE");
            statement.execute("SELECT * FROM expensive_action_policy");
            statement.execute("SELECT '[1,0,0]'::vector <=> '[0,1,0]'::vector");
            statement.execute("DELETE FROM garment");
            statement.execute(
                    """
                    INSERT INTO workflow_slot(job_id,execution_arn,state_machine_arn,execution_name,payload)
                    VALUES ('00000000-0000-4000-8000-000000000006','execution','machine','job','{}')
                    """);
            statement.execute(
                    """
                    INSERT INTO workflow_task(task_arn,job_id)
                    VALUES ('arn:aws:ecs:eu-west-2:123456789012:task/closetos-dev/0123456789abcdef0123456789abcdef',
                        '00000000-0000-4000-8000-000000000006')
                    """);
            statement.execute(
                    "UPDATE workflow_task SET stopped_at = now() WHERE stopped_at IS NULL");
            for (String sql :
                    new String[] {
                        "CREATE TABLE injected(id integer)",
                        "CREATE TEMP TABLE injected(id integer)",
                        "CREATE SCHEMA injected",
                        "ALTER TABLE garment ADD COLUMN injected integer",
                        "DROP TABLE garment",
                        "TRUNCATE garment",
                        "SELECT * FROM flyway_schema_history",
                        "UPDATE flyway_schema_history SET checksum=0",
                        "UPDATE expensive_action_policy SET long_limit=100000",
                        "DELETE FROM expensive_action_policy",
                        "DELETE FROM identity_authentication",
                        "DELETE FROM account_removal",
                        "DELETE FROM workflow_task",
                        "UPDATE workflow_task SET job_id = '00000000-0000-4000-8000-000000000007'",
                        "UPDATE workflow_task SET task_arn = 'foreign'",
                        "UPDATE workflow_task SET observed_at = now()",
                        "UPDATE identity_authentication SET subject_hash = repeat('0',64)",
                        "UPDATE account_removal SET requested_at = now()",
                        "UPDATE workflow_capacity SET maximum_active=16",
                        "CREATE ROLE injected",
                        "SET ROLE " + POSTGRES.getUsername()
                    }) {
                assertThatThrownBy(() -> statement.execute(sql))
                        .as(sql)
                        .isInstanceOf(SQLException.class)
                        .extracting(error -> ((SQLException) error).getSQLState())
                        .isEqualTo("42501");
            }
        }
        try (var connection = connect();
                var statement = connection.createStatement();
                var result =
                        statement.executeQuery(
                                "SELECT rolpassword FROM pg_authid WHERE rolname='closetos_app'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).startsWith("SCRAM-SHA-256$").doesNotContain(password);
        }
    }

    @Test
    void replaysGrantsRemovesPrivilegeDriftAndRotatesCredentials() throws Exception {
        var configured = new HashMap<>(environment);
        String original = UUID.randomUUID() + " initial-runtime-password";
        configured.put("APPLICATION_DATABASE_PASSWORD", original);
        DatabaseMigration.migrate(configured);
        try (var connection = connect();
                var statement = connection.createStatement()) {
            statement.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO closetos_app");
            statement.execute("GRANT UPDATE(maximum_active) ON workflow_capacity TO closetos_app");
            statement.execute(
                    "GRANT SELECT(checksum), UPDATE(checksum) ON flyway_schema_history TO PUBLIC");
        }
        String rotated = UUID.randomUUID() + " rotated-runtime-password";
        configured.put("APPLICATION_DATABASE_PASSWORD", rotated);
        assertThat(DatabaseMigration.migrate(configured).migrationsExecuted).isZero();
        assertThatThrownBy(() -> connectRuntime(original))
                .isInstanceOf(SQLException.class)
                .extracting(error -> ((SQLException) error).getSQLState())
                .isEqualTo("28P01");
        try (var connection = connectRuntime(rotated);
                var statement = connection.createStatement()) {
            statement.execute("SELECT maximum_active FROM workflow_capacity FOR UPDATE");
            statement.execute("SELECT * FROM garment");
            for (String sql :
                    new String[] {
                        "UPDATE workflow_capacity SET maximum_active=16",
                        "SELECT checksum FROM flyway_schema_history",
                        "UPDATE flyway_schema_history SET checksum=0",
                        "UPDATE expensive_action_policy SET long_limit=100000"
                    }) {
                assertThatThrownBy(() -> statement.execute(sql))
                        .as(sql)
                        .isInstanceOf(SQLException.class)
                        .extracting(error -> ((SQLException) error).getSQLState())
                        .isEqualTo("42501");
            }
        }
    }

    @Test
    void refusesUnsafeExistingRolesWithoutInstallingTheNewPassword() throws Exception {
        var configured = new HashMap<>(environment);
        String password = UUID.randomUUID() + " existing-runtime-password";
        configured.put("APPLICATION_DATABASE_PASSWORD", password);
        DatabaseMigration.migrate(configured);
        try (var connection = connect();
                var statement = connection.createStatement()) {
            statement.execute("ALTER ROLE closetos_app CREATEDB");
            try {
                configured.put(
                        "APPLICATION_DATABASE_PASSWORD",
                        UUID.randomUUID() + " replacement-password");
                assertThatThrownBy(() -> DatabaseMigration.migrate(configured))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Application database access could not be configured.")
                        .hasNoCause();
                try (var runtime = connectRuntime(password)) {
                    assertThat(runtime.isValid(5)).isTrue();
                }
            } finally {
                statement.execute("ALTER ROLE closetos_app NOCREATEDB");
            }
        }
    }

    @Test
    void rejectsInvalidApplicationPasswordsBeforeCreatingTheSchema() throws Exception {
        for (String password : new String[] {"", "short", "a".repeat(257), "a".repeat(32) + '\0'}) {
            var configured = new HashMap<>(environment);
            configured.put("APPLICATION_DATABASE_PASSWORD", password);
            assertThatThrownBy(() -> DatabaseMigration.migrate(configured))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("32–256");
        }
        try (var connection = connect();
                var statement = connection.createStatement();
                var result =
                        statement.executeQuery(
                                "SELECT to_regclass('flyway_schema_history') IS NULL")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean(1)).isTrue();
        }
    }

    private java.sql.Connection connectRuntime(String password) throws SQLException {
        return DriverManager.getConnection(
                environment.get("DATABASE_URL"), "closetos_app", password);
    }

    @Test
    void supportsADatabaseOwnerWithCreateRoleButWithoutPostgresSuperuser() throws Exception {
        try (var postgres =
                new PostgreSQLContainer(
                        DockerImageName.parse("pgvector/pgvector:pg18")
                                .asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            String ownerPassword = UUID.randomUUID() + " migration-owner-password";
            try (var connection =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var statement = connection.createStatement()) {
                statement.execute("CREATE ROLE migration_owner LOGIN CREATEDB CREATEROLE");
                connection
                        .unwrap(PGConnection.class)
                        .alterUserPassword(
                                "migration_owner", ownerPassword.toCharArray(), "scram-sha-256");
                statement.execute("CREATE EXTENSION vector");
                statement.execute(
                        "ALTER DATABASE "
                                + postgres.getDatabaseName()
                                + " OWNER TO migration_owner");
            }
            String runtimePassword = UUID.randomUUID() + " runtime-password";
            var configuration =
                    Map.of(
                            "DATABASE_URL",
                            postgres.getJdbcUrl(),
                            "DATABASE_USERNAME",
                            "migration_owner",
                            "DATABASE_PASSWORD",
                            ownerPassword,
                            "APPLICATION_DATABASE_PASSWORD",
                            runtimePassword);
            assertThat(DatabaseMigration.migrate(configuration).migrationsExecuted).isEqualTo(14);
            assertThat(DatabaseMigration.migrate(configuration).migrationsExecuted).isZero();
            try (var connection =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(), "closetos_app", runtimePassword);
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT * FROM garment")) {
                assertThat(result.next()).isFalse();
            }
        }
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
