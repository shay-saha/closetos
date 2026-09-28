package com.closetos.platform.api;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class PersonalDataWriter {
    private final JsonGenerator output;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Set<String> sections = new HashSet<>();
    private long rows;

    public PersonalDataWriter(JsonGenerator output, JdbcTemplate jdbc, JsonMapper json) {
        this.output = output;
        this.jdbc = jdbc;
        this.json = json;
    }

    public void rows(String section, String sql, UUID owner) {
        rows(section, sql, owner, null);
    }

    public void rows(String section, String sql, UUID owner, UnaryOperator<JsonNode> transform) {
        if (!sections.add(section))
            throw new IllegalStateException("Duplicated personal data section.");
        output.writeArrayPropertyStart(section);
        // With the surrounding transaction, PostgreSQL uses a cursor rather than buffering the
        // wardrobe.
        jdbc.query(
                connection -> {
                    var statement = connection.prepareStatement(sql);
                    statement.setFetchSize(128);
                    statement.setObject(1, owner);
                    return statement;
                },
                (org.springframework.jdbc.core.RowCallbackHandler)
                        result -> {
                            String value = result.getString(1);
                            if (transform == null) output.writeRawValue(value);
                            else output.writeTree(transform.apply(json.readTree(value)));
                            rows++;
                        });
        output.writeEndArray();
    }

    public long rowCount() {
        return rows;
    }
}
