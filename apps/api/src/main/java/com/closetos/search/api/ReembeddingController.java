package com.closetos.search.api;

import com.closetos.search.application.ReembeddingJobStore;
import com.closetos.search.application.ReembeddingJobs;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/search/embedding-jobs")
class ReembeddingController {
    private final ReembeddingJobs jobs;
    private final ReembeddingJobStore store;

    ReembeddingController(ReembeddingJobs jobs, ReembeddingJobStore store) {
        this.jobs = jobs;
        this.store = store;
    }

    @PostMapping
    ResponseEntity<ReembeddingJob> create(
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Request request) {
        var job =
                jobs.request(
                        request.wardrobeId(), Boolean.TRUE.equals(request.allWardrobes()), key);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/admin/search/embedding-jobs/" + job.id()))
                .body(job);
    }

    @GetMapping
    List<ReembeddingJob> recent() {
        return store.recent();
    }

    @GetMapping("/{id}")
    ReembeddingJob get(@PathVariable UUID id) {
        return store.get(id);
    }

    record Request(UUID wardrobeId, Boolean allWardrobes) {}
}
