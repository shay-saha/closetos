package com.closetos.media.application;

import com.closetos.platform.api.AccountRemovalPreparation;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class PhotoRemovalPreparation implements AccountRemovalPreparation {
    private final JdbcClient jdbc;

    PhotoRemovalPreparation(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void prepare(UUID request, UUID owner) {
        jdbc.sql(
                        """
                WITH originals AS (
                    SELECT source_s3_key AS source_key FROM garment_image WHERE user_id = :owner
                    UNION
                    SELECT o.payload->>'sourceKey' FROM outbox_event o
                        JOIN wardrobe w ON w.id = o.wardrobe_id
                        WHERE w.owner_id = :owner
                            AND (o.event_type IN ('START_PROCESSING', 'DELETE_ORIGINAL')
                                OR (o.event_type = 'DELETE_MEDIA' AND jsonb_exists(o.payload, 'sourceKey')))
                    UNION
                    SELECT (o.payload->>'prefix') || 'original.' || extension
                        FROM outbox_event o JOIN wardrobe w ON w.id = o.wardrobe_id
                        CROSS JOIN unnest(ARRAY['jpg','jpeg','png','webp','heic','heif']) extension
                        WHERE w.owner_id = :owner AND o.event_type = 'DELETE_MEDIA'
                            AND NOT jsonb_exists(o.payload, 'sourceKey')
                )
                INSERT INTO account_removal_source (request_id, source_key)
                    SELECT :request, source_key FROM originals
                    ON CONFLICT DO NOTHING
                """)
                .param("request", request)
                .param("owner", owner)
                .update();
    }
}
