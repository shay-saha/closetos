package com.closetos.media.application;

import com.closetos.media.api.AccountPhotoErasure;
import com.closetos.media.api.ObjectStoragePort;
import com.closetos.media.api.PhotoCachePort;
import com.closetos.platform.api.MediaErasureAdmission;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class RemovedAccountPhotos implements AccountPhotoErasure {
    private final JdbcClient jdbc;
    private final ObjectStoragePort storage;
    private final MediaErasureAdmission admission;
    private final PhotoCachePort cache;

    RemovedAccountPhotos(
            JdbcClient jdbc,
            ObjectStoragePort storage,
            MediaErasureAdmission admission,
            PhotoCachePort cache) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.admission = admission;
        this.cache = cache;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean erase(UUID request, UUID owner, UUID lease) {
        if (!admission.allowed(request, owner, lease)) return false;
        var originals =
                jdbc.sql(
                                "SELECT source_key FROM account_removal_source WHERE request_id = :request ORDER BY source_key")
                        .param("request", request)
                        .query(String.class)
                        .list();
        storage.eraseOwner(owner, originals);
        if (!admission.allowed(request, owner, lease)) return false;
        return cache.erase(request, owner);
    }
}
