package com.closetos.wardrobe.application;

import com.closetos.identity.api.IdentityAccess;
import com.closetos.platform.api.DomainException;
import com.closetos.wardrobe.api.WardrobeAccess;
import com.closetos.wardrobe.api.WardrobeDetails;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WardrobeService implements WardrobeAccess {
    private final IdentityAccess identity;
    private final JdbcClient jdbc;

    public WardrobeService(IdentityAccess identity, JdbcClient jdbc) {
        this.identity = identity;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public UUID currentWardrobeId() {
        return current().id();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(UUID wardrobe) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM wardrobe WHERE id = :id)")
                .param("id", wardrobe)
                .query(Boolean.class)
                .single();
    }

    @Transactional
    public WardrobeDetails current() {
        UUID owner = identity.currentUserId();
        jdbc.sql(
                        """
                INSERT INTO wardrobe (id, owner_id, name) VALUES (:id, :owner, 'My wardrobe')
                ON CONFLICT (owner_id) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("owner", owner)
                .update();
        return jdbc.sql(
                        "SELECT id, name, version, created_at FROM wardrobe WHERE owner_id = :owner")
                .param("owner", owner)
                .query(WardrobeDetails.class)
                .single();
    }

    @Transactional
    public WardrobeDetails rename(String name, long version) {
        UUID wardrobe = currentWardrobeId();
        int updated =
                jdbc.sql(
                                """
                UPDATE wardrobe SET name = :name, version = version + 1, updated_at = now()
                WHERE id = :id AND version = :version
                """)
                        .param("name", name.strip())
                        .param("id", wardrobe)
                        .param("version", version)
                        .update();
        if (updated == 0) {
            throw DomainException.conflict();
        }
        return current();
    }
}
