package com.closetos.identity.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccountRemovalTasks {
    Optional<Work> claim();

    Optional<Work> eraseData(Work work);

    Optional<Work> checkpoint(Work work, Step step);

    boolean defer(Work work, Failure failure, Duration delay);

    boolean complete(Work work);

    enum Step {
        WORKERS,
        PROVIDER,
        MEDIA
    }

    enum Failure {
        WORKERS_PENDING,
        MEDIA_LINKS_ACTIVE,
        MEDIA_PENDING,
        PROVIDER_UNAVAILABLE,
        DEPENDENCY_UNAVAILABLE
    }

    record Work(
            UUID requestId,
            UUID ownerId,
            String providerSubject,
            int attemptCount,
            UUID leaseToken,
            Instant leaseUntil,
            Instant dataErasedAt,
            Instant workersDrainedAt,
            Instant providerErasedAt,
            Instant mediaErasedAt,
            Instant mediaPurgeAfter) {
        @Override
        public String toString() {
            return "AccountRemovalWork[requestId="
                    + requestId
                    + ", attemptCount="
                    + attemptCount
                    + "]";
        }
    }
}
