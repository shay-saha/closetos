package com.closetos.search.infrastructure;

import com.closetos.platform.api.DomainException;
import com.closetos.search.api.EmbeddingProviderPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class EmbeddingConfiguration {
    @Bean
    @ConditionalOnMissingBean(EmbeddingProviderPort.class)
    @ConditionalOnProperty(
            name = "closetos.search.provider",
            havingValue = "auto",
            matchIfMissing = true)
    EmbeddingProviderPort unavailableEmbeddings() {
        return new EmbeddingProviderPort() {
            public ModelInfo model() {
                throw unavailable();
            }

            public VectorResult embed(Input input, ModelInfo expected) {
                throw unavailable();
            }

            private DomainException unavailable() {
                return new DomainException(
                        503, "EMBEDDINGS_UNAVAILABLE", "Semantic search is not configured.");
            }
        };
    }
}
