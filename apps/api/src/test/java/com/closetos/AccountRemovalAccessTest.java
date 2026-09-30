package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.closetos.identity.api.AccountRemovalAccess;
import com.closetos.identity.api.IdentityAccess;
import com.closetos.platform.api.DomainException;
import com.closetos.platform.api.IdentityRevocations;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class AccountRemovalAccessTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityAccess identity;
    @Autowired AccountRemovalAccess removals;
    @Autowired IdentityRevocations revocations;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MeterRegistry metrics;

    @Test
    void removalRevokesEveryAuthenticatedRouteWithoutAffectingAnotherAccount() throws Exception {
        String subject = subject();
        String other = subject();
        mvc.perform(get("/api/v1/me").with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk());
        var request = as(subject, removals::request);
        assertThat(as(subject, removals::request)).isEqualTo(request);
        assertThat(revocations.revoked(subject)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM account_removal WHERE id = ?",
                                Integer.class,
                                request.requestId()))
                .isEqualTo(1);
        for (String path :
                new String[] {
                    "/api/v1/me", "/api/v1/me/data", "/api/v1/garments", "/api/v1/admin/reembedding"
                }) {
            var response =
                    mvc.perform(
                                    get(path)
                                            .with(
                                                    jwt().jwt(token -> token.subject(subject))
                                                            .authorities(
                                                                    new SimpleGrantedAuthority(
                                                                            "ROLE_CLOSETOS_ADMIN"))))
                            .andExpect(status().isUnauthorized())
                            .andReturn()
                            .getResponse();
            assertThat(response.getContentAsString())
                    .contains("ACCOUNT_REMOVED")
                    .doesNotContain(subject);
        }
        assertThatThrownBy(() -> as(subject, identity::currentUserId))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).code())
                .isEqualTo("ACCOUNT_REMOVED");
        mvc.perform(get("/api/v1/me").with(jwt().jwt(token -> token.subject(other))))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        assertThat(revocations.revoked(other)).isFalse();
    }

    @Test
    void removingPersonalIdentifiersKeepsTheOldTokenRevokedAfterProfilePurging() throws Exception {
        String subject = subject();
        var request = as(subject, removals::request);
        var owner =
                jdbc.queryForObject(
                        "SELECT owner_id FROM account_removal WHERE id = ?",
                        UUID.class,
                        request.requestId());
        jdbc.update("DELETE FROM user_profile WHERE id = ?", owner);
        jdbc.update(
                "UPDATE account_removal SET owner_id = NULL, provider_subject = NULL, state = 'COMPLETE', completed_at = now() WHERE id = ?",
                request.requestId());
        assertThat(revocations.revoked(subject)).isTrue();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT owner_id, provider_subject FROM account_removal WHERE id = ?",
                                request.requestId()))
                .allSatisfy((key, value) -> assertThat(value).isNull());
        mvc.perform(get("/api/v1/me").with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isUnauthorized());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO user_profile (id, cognito_sub, display_name) VALUES (?, ?, 'Restored')",
                                        UUID.randomUUID(),
                                        subject))
                .isInstanceOf(DataAccessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM user_profile WHERE cognito_sub = ?",
                                Integer.class,
                                subject))
                .isZero();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE identity_authentication SET revoked = false WHERE subject_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')",
                                        subject))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void removalRequestsCannotBeRedirectedToAnotherAccount() {
        String subject = subject();
        var request = as(subject, removals::request);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE account_removal SET owner_id = ? WHERE id = ?",
                                        UUID.randomUUID(),
                                        request.requestId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE account_removal SET provider_subject = ? WHERE id = ?",
                                        subject(),
                                        request.requestId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE user_profile SET cognito_sub = ? WHERE cognito_sub = ?",
                                        subject(),
                                        subject))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rollingBackARemovalLeavesTheAccountUsableAndDoesNotLeaveARequest() {
        String subject = subject();
        double requestsBefore = metrics.counter("privacy.account.removal.requests").count();
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(
                status -> {
                    as(subject, removals::request);
                    assertThat(revocations.revoked(subject)).isTrue();
                    status.setRollbackOnly();
                });
        assertThat(metrics.counter("privacy.account.removal.requests").count())
                .isEqualTo(requestsBefore);
        assertThat(revocations.revoked(subject)).isFalse();
        assertThat(as(subject, identity::currentUserId)).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM account_removal WHERE subject_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')",
                                Integer.class,
                                subject))
                .isZero();
    }

    @Test
    void concurrentRemovalRequestsPersistOneStableReceipt() throws Exception {
        String subject = subject();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var requests =
                    java.util.stream.IntStream.range(0, 8)
                            .mapToObj(
                                    index -> executor.submit(() -> as(subject, removals::request)))
                            .toList();
            var first = requests.getFirst().get(20, TimeUnit.SECONDS);
            for (var result : requests)
                assertThat(result.get(20, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM account_removal WHERE id = ?",
                                    Integer.class,
                                    first.requestId()))
                    .isEqualTo(1);
        }
    }

    @Test
    void anObsoleteRepeatableReadSnapshotCannotRecreateARevokedProfile() {
        String subject = subject();
        as(subject, identity::currentUserId);
        var snapshot = new TransactionTemplate(transactions);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(
                        () ->
                                snapshot.executeWithoutResult(
                                        status -> {
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM identity_authentication",
                                                    Integer.class);
                                            try (var executor =
                                                    Executors.newSingleThreadExecutor()) {
                                                try {
                                                    executor.submit(
                                                                    () -> {
                                                                        as(
                                                                                subject,
                                                                                removals::request);
                                                                        jdbc.update(
                                                                                "DELETE FROM user_profile WHERE cognito_sub = ?",
                                                                                subject);
                                                                    })
                                                            .get(20, TimeUnit.SECONDS);
                                                } catch (Exception error) {
                                                    throw new IllegalStateException(error);
                                                }
                                            }
                                            jdbc.update(
                                                    "INSERT INTO user_profile (id, cognito_sub, display_name) VALUES (?, ?, 'Old snapshot')",
                                                    UUID.randomUUID(),
                                                    subject);
                                        }))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .isInstanceOf(SQLException.class)
                .extracting(error -> ((SQLException) error).getSQLState())
                .isEqualTo("40001");
        assertThat(revocations.revoked(subject)).isTrue();
    }

    @Test
    void anonymousActorsCannotRequestRemoval() {
        assertThatThrownBy(removals::request).isInstanceOf(DomainException.class);
        assertThat(revocations.revoked(null)).isTrue();
        assertThat(revocations.revoked(" ")).isTrue();
        assertThat(revocations.revoked("x".repeat(129))).isTrue();
    }

    private static String subject() {
        return "removal-" + UUID.randomUUID();
    }

    private static <T> T as(String subject, Supplier<T> operation) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new JwtAuthenticationToken(
                        Jwt.withTokenValue("test-token")
                                .header("alg", "RS256")
                                .subject(subject)
                                .build()));
        SecurityContextHolder.setContext(context);
        try {
            return operation.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
