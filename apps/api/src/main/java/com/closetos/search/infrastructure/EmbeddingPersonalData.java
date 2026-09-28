package com.closetos.search.infrastructure;

import com.closetos.platform.api.PersonalDataContributor;
import com.closetos.platform.api.PersonalDataWriter;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class EmbeddingPersonalData implements PersonalDataContributor {
    @Override
    public void write(PersonalDataWriter writer, UUID owner, UUID wardrobe, Instant photoExpiry) {
        writer.rows(
                "embeddings",
                "SELECT (to_jsonb(e) - 'embedding') || jsonb_build_object('embedding', e.embedding::text::jsonb) FROM garment_embedding e WHERE wardrobe_id = ? ORDER BY garment_id, model_key",
                wardrobe);
        writer.rows(
                "embeddingModels",
                """
                WITH scope AS (SELECT CAST(? AS uuid) AS wardrobe_id)
                SELECT to_jsonb(m) FROM embedding_model m WHERE m.model_key IN (
                    SELECT model_key FROM garment_embedding WHERE wardrobe_id = (SELECT wardrobe_id FROM scope)
                    UNION SELECT model_key FROM garment_embedding_work WHERE wardrobe_id = (SELECT wardrobe_id FROM scope)
                ) ORDER BY model_key
                """,
                wardrobe);
        writer.rows(
                "embeddingStatus",
                "SELECT to_jsonb(w) - 'lease_owner' - 'lease_until' FROM garment_embedding_work w WHERE wardrobe_id = ? ORDER BY garment_id, model_key",
                wardrobe);
    }
}
