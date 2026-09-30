package com.closetos.identity.application;

import com.closetos.identity.api.IdentityAccess;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CurrentIdentity implements IdentityAccess {
    private final JdbcClient jdbc;

    private final RevokedIdentities revocations;

    CurrentIdentity(JdbcClient jdbc, RevokedIdentities revocations) {
        this.jdbc = jdbc;
        this.revocations = revocations;
    }

    @Override
    @Transactional
    public UUID currentUserId() {
        var token = AuthenticatedSubject.token();
        String subject = token.getSubject();
        revocations.requireAvailable(subject);
        var existing =
                jdbc.sql("SELECT id FROM user_profile WHERE cognito_sub = :subject")
                        .param("subject", subject)
                        .query(UUID.class)
                        .optional();
        if (existing.isPresent()) return existing.get();
        revocations.lock(subject);
        revocations.requireAvailable(subject);
        String displayName = token.getClaimAsString("name");
        jdbc.sql(
                        """
                INSERT INTO user_profile (id, cognito_sub, display_name)
                VALUES (:id, :subject, :name) ON CONFLICT (cognito_sub) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("subject", subject)
                .param(
                        "name",
                        displayName == null
                                ? "My wardrobe"
                                : displayName.substring(0, Math.min(120, displayName.length())))
                .update();
        return jdbc.sql("SELECT id FROM user_profile WHERE cognito_sub = :subject")
                .param("subject", subject)
                .query(UUID.class)
                .single();
    }
}
