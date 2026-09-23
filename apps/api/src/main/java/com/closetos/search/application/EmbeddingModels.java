package com.closetos.search.application;

import com.closetos.search.api.EmbeddingModel;
import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmbeddingModels {
    private final JdbcClient jdbc;

    public EmbeddingModels(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void register(ModelInfo info) {
        var model = info.model();
        jdbc.sql(
                        """
                INSERT INTO embedding_model(model_key, provider, model_id, model_version, pipeline_version, dimensions)
                VALUES (:key, :provider, :id, :version, :pipeline, :dimensions) ON CONFLICT (model_key) DO NOTHING
                """)
                .param("key", info.modelKey())
                .param("provider", model.provider())
                .param("id", model.modelId())
                .param("version", model.modelVersion())
                .param("pipeline", model.pipelineVersion())
                .param("dimensions", model.dimensions())
                .update();
        var existing =
                jdbc.sql(
                                "SELECT provider, model_id, model_version, pipeline_version, dimensions FROM embedding_model WHERE model_key = :key")
                        .param("key", info.modelKey())
                        .query(EmbeddingModel.class)
                        .single();
        if (!existing.equals(model))
            throw new IllegalStateException(
                    "An embedding model key was reused with a different identity.");
    }
}
