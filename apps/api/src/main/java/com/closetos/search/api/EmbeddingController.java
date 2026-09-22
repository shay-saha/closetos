package com.closetos.search.api;

import com.closetos.search.application.EmbeddingStatusService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class EmbeddingController {
    private final EmbeddingStatusService status;

    EmbeddingController(EmbeddingStatusService status) {
        this.status = status;
    }

    @GetMapping("/api/v1/garments/{id}/embedding")
    EmbeddingStatus get(@PathVariable UUID id) {
        return status.get(id);
    }
}
