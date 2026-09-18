package com.closetos.identity.application;

import com.closetos.identity.api.IdentityAccess;
import com.closetos.platform.api.DomainException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CurrentIdentity implements IdentityAccess {
    private final JdbcClient jdbc;

    CurrentIdentity(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public UUID currentUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new DomainException(401, "AUTHENTICATION", "Sign in to continue.");
        }
        String subject = token.getToken().getSubject();
        String displayName = token.getToken().getClaimAsString("name");
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
