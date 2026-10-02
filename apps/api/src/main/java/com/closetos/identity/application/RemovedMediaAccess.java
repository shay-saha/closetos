package com.closetos.identity.application;

import com.closetos.platform.api.MediaErasureAdmission;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class RemovedMediaAccess implements MediaErasureAdmission {
    private final JdbcClient jdbc;
    private final Clock clock;

    RemovedMediaAccess(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean allowed(UUID request, UUID owner, UUID lease) {
        return jdbc.sql(
                        """
                SELECT EXISTS (SELECT 1 FROM account_removal r
                    WHERE r.id = :request AND r.owner_id = :owner AND r.lease_token = :lease
                        AND r.state = 'REMOVING' AND r.lease_until > :now
                        AND r.data_erased_at IS NOT NULL AND r.workers_drained_at IS NOT NULL
                        AND r.media_purge_after <= :now
                        AND NOT EXISTS (SELECT 1 FROM user_profile p WHERE p.id = r.owner_id))
                """)
                .param("request", request)
                .param("owner", owner)
                .param("lease", lease)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .query(Boolean.class)
                .single();
    }
}
