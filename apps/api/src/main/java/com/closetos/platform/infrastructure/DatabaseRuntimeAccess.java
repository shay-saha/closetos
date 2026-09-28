package com.closetos.platform.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.postgresql.PGConnection;

final class DatabaseRuntimeAccess {
    private DatabaseRuntimeAccess() {}

    static void validatePassword(String password) {
        if (password != null
                && (password.length() < 32
                        || password.length() > 256
                        || password.indexOf('\0') >= 0))
            throw new IllegalArgumentException(
                    "Application database password must contain 32–256 characters without null bytes.");
    }

    static void reconcile(
            String url, String username, String password, String applicationPassword) {
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(1129074515, 1)");
                try (var source =
                        DatabaseRuntimeAccess.class.getResourceAsStream("/db/runtime-access.sql")) {
                    if (source == null)
                        throw new IllegalStateException("Runtime database grants are missing.");
                    statement.execute(new String(source.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            // The driver sends a salted SCRAM verifier instead of placing plaintext in SQL or logs.
            connection
                    .unwrap(PGConnection.class)
                    .alterUserPassword(
                            "closetos_app", applicationPassword.toCharArray(), "scram-sha-256");
            connection.commit();
        } catch (SQLException | IOException error) {
            // SQL diagnostics can include credential verifiers; keep them out of process logs.
            throw new IllegalStateException("Application database access could not be configured.");
        }
    }
}
