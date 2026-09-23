package com.closetos.search.application;

import com.closetos.platform.api.DomainException;
import com.closetos.search.api.EmbeddingProviderPort;
import com.closetos.search.api.ReembeddingJob;
import com.closetos.wardrobe.api.WardrobeAccess;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class ReembeddingJobs {
    private final ReembeddingJobStore store;
    private final EmbeddingProviderPort provider;
    private final WardrobeAccess wardrobes;

    public ReembeddingJobs(
            ReembeddingJobStore store, EmbeddingProviderPort provider, WardrobeAccess wardrobes) {
        this.store = store;
        this.provider = provider;
        this.wardrobes = wardrobes;
    }

    public ReembeddingJob request(UUID wardrobe, boolean allWardrobes, UUID key) {
        if (allWardrobes && wardrobe != null)
            throw DomainException.invalid("Choose a wardrobe or all wardrobes.");
        UUID scope =
                allWardrobes ? null : wardrobe == null ? wardrobes.currentWardrobeId() : wardrobe;
        if (scope != null && !wardrobes.exists(scope)) throw DomainException.notFound("Wardrobe");
        String requester = SecurityContextHolder.getContext().getAuthentication().getName();
        var repeated = store.existing(requester, key, scope);
        UUID id = repeated.orElseGet(() -> store.create(requester, key, scope, provider.model()));
        return store.get(id);
    }
}
