package com.closetos.garment.infrastructure;

import com.closetos.garment.api.GarmentFilter;
import com.closetos.platform.api.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
class CatalogueCursor {
    private final JsonMapper json;

    CatalogueCursor(JsonMapper json) {
        this.json = json;
    }

    record Position(UUID id, String value, String query) {}

    String encode(UUID id, String value, GarmentFilter filter, String scope) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        json.writeValueAsBytes(
                                new Position(id, value, fingerprint(filter, scope))));
    }

    Position decode(String cursor, GarmentFilter filter, String scope) {
        try {
            Position position =
                    json.readValue(Base64.getUrlDecoder().decode(cursor), Position.class);
            if (position.id() == null
                    || position.value() == null
                    || !fingerprint(filter, scope).equals(position.query())) {
                throw DomainException.invalid("This pagination cursor does not match the filters.");
            }
            return position;
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException exception) {
            throw DomainException.invalid("Invalid pagination cursor.");
        }
    }

    private String fingerprint(GarmentFilter filter, String scope) {
        try {
            ObjectNode query = json.valueToTree(filter);
            query.remove("cursor");
            query.remove("limit");
            query.put("scope", scope);
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(query.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
