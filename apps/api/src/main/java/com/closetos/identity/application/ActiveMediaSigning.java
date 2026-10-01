package com.closetos.identity.application;

import com.closetos.platform.api.DomainException;
import com.closetos.platform.api.MediaSigningAdmission;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ActiveMediaSigning implements MediaSigningAdmission {
    private final JdbcClient jdbc;

    ActiveMediaSigning(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public <T> T sign(UUID owner, Supplier<T> operation) {
        // Revocation waits for admitted signers; old repeatable-read snapshots cannot regain
        // admission.
        var available =
                jdbc.sql(
                                """
                SELECT p.id FROM user_profile p JOIN identity_authentication a
                    ON a.subject_hash = encode(sha256(convert_to(p.cognito_sub, 'UTF8')), 'hex')
                WHERE p.id = :owner AND NOT a.revoked
                FOR SHARE OF a FOR KEY SHARE OF p
                """)
                        .param("owner", owner)
                        .query(UUID.class)
                        .optional();
        if (available.isEmpty())
            throw new DomainException(
                    401, "ACCOUNT_REMOVED", "This account is no longer available.");
        return operation.get();
    }
}
