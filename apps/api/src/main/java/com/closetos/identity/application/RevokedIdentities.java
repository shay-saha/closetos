package com.closetos.identity.application;

import com.closetos.platform.api.DomainException;
import com.closetos.platform.api.IdentityRevocations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class RevokedIdentities implements IdentityRevocations {
    private final JdbcClient jdbc;

    RevokedIdentities(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean revoked(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 128) return true;
        return jdbc.sql(
                        """
                SELECT EXISTS (SELECT 1 FROM identity_authentication
                    WHERE revoked AND subject_hash = encode(sha256(convert_to(CAST(:subject AS text), 'UTF8')), 'hex'))
                """)
                .param("subject", subject)
                .query(Boolean.class)
                .single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String subject) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:subject AS text), 937147))")
                .param("subject", subject)
                .query(Object.class)
                .single();
    }

    void requireAvailable(String subject) {
        if (revoked(subject))
            throw new DomainException(401, "ACCOUNT_REMOVED", "This account has been removed.");
    }
}
