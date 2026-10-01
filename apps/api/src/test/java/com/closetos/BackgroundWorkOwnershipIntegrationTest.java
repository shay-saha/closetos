package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.closetos.platform.api.OutboxAccess;
import com.closetos.platform.api.OutboxQueue;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class BackgroundWorkOwnershipIntegrationTest extends PostgresIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired OutboxAccess outbox;
    @Autowired OutboxQueue queue;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void profileRemovalErasesQueuedPublishedAndLeasedPayloadsWithoutTouchingAnotherOwner() {
        var owner = wardrobe();
        var other = wardrobe();
        String type = "REMOVAL_TEST_" + UUID.randomUUID();
        enqueue(owner.wardrobe(), type);
        enqueue(owner.wardrobe(), type + "_PUBLISHED");
        enqueue(other.wardrobe(), type + "_OTHER");
        var leased = queue.claim(Set.of(type)).getFirst();
        jdbc.sql(
                        "UPDATE outbox_event SET published_at = now() WHERE wardrobe_id = :wardrobe AND event_type = :type")
                .param("wardrobe", owner.wardrobe())
                .param("type", type + "_PUBLISHED")
                .update();
        assertThat(count(owner.wardrobe())).isEqualTo(2);

        jdbc.sql("DELETE FROM user_profile WHERE id = :owner")
                .param("owner", owner.owner())
                .update();

        assertThat(count(owner.wardrobe())).isZero();
        assertThat(count(other.wardrobe())).isEqualTo(1);
        assertThat(queue.claim(Set.of(type))).isEmpty();
        // Responses from a worker which claimed the old event cannot restore its erased payload.
        queue.published(leased);
        queue.failed(leased, "Late worker failure", false);
        queue.defer(leased, Duration.ZERO);
        assertThat(count(owner.wardrobe())).isZero();
        assertThatThrownBy(() -> enqueue(owner.wardrobe(), type))
                .isInstanceOf(DataAccessException.class);
        assertThat(count(other.wardrobe())).isEqualTo(1);
    }

    @Test
    void removalRollbackKeepsBothTheProfileAndItsBackgroundWorkUsable() {
        var owner = wardrobe();
        String type = "REMOVAL_ROLLBACK_" + UUID.randomUUID();
        enqueue(owner.wardrobe(), type);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            jdbc.sql("DELETE FROM user_profile WHERE id = :owner")
                                    .param("owner", owner.owner())
                                    .update();
                            assertThat(count(owner.wardrobe())).isZero();
                            status.setRollbackOnly();
                        });
        assertThat(count(owner.wardrobe())).isEqualTo(1);
        assertThat(queue.claim(Set.of(type))).hasSize(1);
    }

    private void enqueue(UUID wardrobe, String type) {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                outbox.enqueue(
                                        wardrobe,
                                        "garment",
                                        UUID.randomUUID(),
                                        type,
                                        "ownership-test:" + UUID.randomUUID(),
                                        Map.of(
                                                "wardrobeId",
                                                wardrobe,
                                                "privateMetadata",
                                                List.of("Personal note"))));
    }

    private long count(UUID wardrobe) {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE wardrobe_id = :wardrobe")
                .param("wardrobe", wardrobe)
                .query(Long.class)
                .single();
    }

    private Owner wardrobe() {
        UUID owner = UUID.randomUUID(), wardrobe = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO user_profile(id, cognito_sub, display_name) VALUES (:id, :subject, 'Ownership test')")
                .param("id", owner)
                .param("subject", "background-" + owner)
                .update();
        jdbc.sql("INSERT INTO wardrobe(id, owner_id, name) VALUES (:id, :owner, 'Wardrobe')")
                .param("id", wardrobe)
                .param("owner", owner)
                .update();
        return new Owner(owner, wardrobe);
    }

    private record Owner(UUID owner, UUID wardrobe) {}
}
