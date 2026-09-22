package com.closetos.search.infrastructure;

import com.closetos.platform.api.DomainException;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SearchCursor {
    private final JsonMapper json;

    public SearchCursor(JsonMapper json) {
        this.json = json;
    }

    public record Position(UUID id, double score, String fingerprint) {}

    public String encode(UUID id, double score, String fingerprint) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.writeValueAsBytes(new Position(id, score, fingerprint)));
    }

    public Position decode(String cursor, String fingerprint) {
        try {
            var position = json.readValue(Base64.getUrlDecoder().decode(cursor), Position.class);
            if (position.id() == null
                    || !Double.isFinite(position.score())
                    || !fingerprint.equals(position.fingerprint()))
                throw DomainException.invalid(
                        "This search cursor no longer matches the query. Start the search again.");
            return position;
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException exception) {
            throw DomainException.invalid("Invalid search cursor.");
        }
    }
}
