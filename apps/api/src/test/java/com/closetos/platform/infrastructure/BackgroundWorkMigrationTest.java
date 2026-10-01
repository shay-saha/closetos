package com.closetos.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class BackgroundWorkMigrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg18")
                            .asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private Map<String, String> environment;

    @BeforeEach
    void legacyDatabase() {
        String database = "background_migration_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()))
                .execute("CREATE DATABASE " + database);
        String url =
                "jdbc:postgresql://"
                        + POSTGRES.getHost()
                        + ":"
                        + POSTGRES.getMappedPort(5432)
                        + "/"
                        + database;
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        environment =
                Map.of(
                        "DATABASE_URL",
                        url,
                        "DATABASE_USERNAME",
                        POSTGRES.getUsername(),
                        "DATABASE_PASSWORD",
                        POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("014")
                .load()
                .migrate();
    }

    @Test
    void adoptsLegacyOwnersIncludingDeletedImagesAndKeepsOtherUsersInGlobalJobs() {
        var owner = wardrobe();
        var other = wardrobe();
        UUID garment = UUID.randomUUID(), image = UUID.randomUUID();
        String prefix =
                "users/" + owner.owner() + "/garments/" + garment + "/images/" + image + "/";
        jdbc.update(
                "INSERT INTO garment(id, wardrobe_id, name, category, created_at, updated_at) VALUES (?, ?, 'Shirt', 'TOP', now(), now())",
                garment,
                owner.wardrobe());
        jdbc.update(
                """
                INSERT INTO garment_image(id, garment_id, wardrobe_id, user_id, image_role, original_filename,
                    mime_type, source_s3_key, expected_size, source_checksum, upload_key, processing_status)
                VALUES (?, ?, ?, ?, 'FRONT', 'shirt.png', 'image/png', ?, 10, 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=', ?, 'UPLOADED')
                """,
                image,
                garment,
                owner.wardrobe(),
                owner.owner(),
                prefix + "original.png",
                UUID.randomUUID());
        UUID imageEvent = event("image", image, "START_PROCESSING", "{}");
        UUID garmentEvent = event("garment", garment, "GENERATE_EMBEDDING", "{}");
        UUID deletedImageEvent =
                event(
                        "image",
                        UUID.randomUUID(),
                        "DELETE_MEDIA",
                        "{\"prefix\":\"" + prefix + "\"}");
        UUID originalEvent =
                event(
                        "image",
                        UUID.randomUUID(),
                        "DELETE_ORIGINAL",
                        "{\"sourceKey\":\"" + prefix + "original.png\"}");
        UUID scopedEvent =
                event(
                        "garment",
                        UUID.randomUUID(),
                        "REBUILD_EMBEDDING",
                        "{\"wardrobeId\":\"" + owner.wardrobe() + "\"}");
        UUID otherEvent =
                event(
                        "garment",
                        UUID.randomUUID(),
                        "REBUILD_EMBEDDING",
                        "{\"wardrobeId\":\"" + other.wardrobe() + "\"}");
        UUID obsoleteEvent = event("garment", UUID.randomUUID(), "GENERATE_EMBEDDING", "{}");
        UUID completedEvent = event("image", UUID.randomUUID(), "DELETE_MEDIA", "{}");
        jdbc.update("UPDATE outbox_event SET published_at = now() WHERE id = ?", completedEvent);
        jdbc.update(
                "UPDATE outbox_event SET lease_until = now() + interval '6 minutes', publish_attempts = 1 WHERE id = ?",
                imageEvent);
        String model = "a".repeat(64);
        jdbc.update(
                "INSERT INTO embedding_model(model_key, provider, model_id, pipeline_version, dimensions) VALUES (?, 'test', 'test-model', '1', 256)",
                model);
        UUID globalJob = job(null, model), scopedJob = job(owner.wardrobe(), model);
        jdbc.update(
                "INSERT INTO reembedding_item(job_id, event_id) VALUES (?, ?), (?, ?), (?, ?)",
                globalJob,
                scopedEvent,
                globalJob,
                otherEvent,
                scopedJob,
                obsoleteEvent);

        assertThat(DatabaseMigration.migrate(environment).migrationsExecuted).isEqualTo(1);
        assertThat(
                        jdbc.queryForList(
                                "SELECT wardrobe_id FROM outbox_event WHERE id IN (?, ?, ?, ?, ?)",
                                UUID.class,
                                imageEvent,
                                garmentEvent,
                                deletedImageEvent,
                                originalEvent,
                                scopedEvent))
                .containsOnly(owner.wardrobe())
                .hasSize(5);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM outbox_event WHERE id IN (?, ?)",
                                Integer.class,
                                obsoleteEvent,
                                completedEvent))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM reembedding_item WHERE job_id = ?",
                                Integer.class,
                                scopedJob))
                .isZero();

        jdbc.update("DELETE FROM user_profile WHERE id = ?", owner.owner());

        assertThat(jdbc.queryForList("SELECT id FROM outbox_event", UUID.class))
                .containsExactly(otherEvent);
        assertThat(jdbc.queryForList("SELECT id FROM reembedding_job", UUID.class))
                .containsExactly(globalJob);
        assertThat(jdbc.queryForList("SELECT event_id FROM reembedding_item", UUID.class))
                .containsExactly(otherEvent);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM wardrobe WHERE id = ?",
                                Integer.class,
                                other.wardrobe()))
                .isEqualTo(1);
    }

    @Test
    void anUnassignedPendingDeletionRefusesMigrationWithoutLosingItsRecoveryRecord() {
        UUID id =
                event(
                        "image",
                        UUID.randomUUID(),
                        "DELETE_MEDIA",
                        "{\"prefix\":\"users/"
                                + UUID.randomUUID()
                                + "/garments/old/images/old/\"}");
        assertThatThrownBy(() -> DatabaseMigration.migrate(environment))
                .isInstanceOf(RuntimeException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload->>'prefix' FROM outbox_event WHERE id = ?",
                                String.class,
                                id))
                .startsWith("users/");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'outbox_event' AND column_name = 'wardrobe_id'",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT max(version) FROM flyway_schema_history WHERE success",
                                String.class))
                .isEqualTo("014");
    }

    private Owner wardrobe() {
        UUID owner = UUID.randomUUID(), wardrobe = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO user_profile(id, cognito_sub, display_name) VALUES (?, ?, 'Migration test')",
                owner,
                "background-migration-" + owner);
        jdbc.update(
                "INSERT INTO wardrobe(id, owner_id, name) VALUES (?, ?, 'Wardrobe')",
                wardrobe,
                owner);
        return new Owner(owner, wardrobe);
    }

    private UUID event(String aggregateType, UUID aggregate, String type, String payload) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO outbox_event(id, aggregate_type, aggregate_id, event_type, idempotency_key, payload) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))",
                id,
                aggregateType,
                aggregate,
                type,
                "migration:" + id,
                payload);
        return id;
    }

    private UUID job(UUID wardrobe, String model) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO reembedding_job(id, requested_by, request_key, wardrobe_id, model_key, created_at) VALUES (?, 'migration-admin', ?, ?, ?, now())",
                id,
                UUID.randomUUID(),
                wardrobe,
                model);
        return id;
    }

    private record Owner(UUID owner, UUID wardrobe) {}
}
