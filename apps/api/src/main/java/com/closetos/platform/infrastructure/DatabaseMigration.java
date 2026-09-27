package com.closetos.platform.infrastructure;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

public final class DatabaseMigration {
    private DatabaseMigration() {}

    public static void main(String[] arguments) {
        if (arguments.length != 0)
            throw new IllegalArgumentException("Migration accepts no arguments.");
        migrate(System.getenv());
    }

    static MigrateResult migrate(Map<String, String> environment) {
        String url = required(environment, "DATABASE_URL");
        String username = required(environment, "DATABASE_USERNAME");
        String password = required(environment, "DATABASE_PASSWORD");
        if (!url.startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException(
                    "Migration requires an explicit PostgreSQL JDBC URL.");
        return Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .connectRetries(2)
                .lockRetryCount(12)
                .load()
                .migrate();
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Migration requires " + name + ".");
        return value;
    }
}
