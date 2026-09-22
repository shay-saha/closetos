package com.closetos.garment.infrastructure;

import com.closetos.garment.domain.Garment;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface GarmentRepository extends JpaRepository<Garment, UUID> {
    Optional<Garment> findByIdAndWardrobeId(UUID id, UUID wardrobeId);

    List<Garment> findByWardrobeIdAndIdIn(UUID wardrobeId, List<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select g from Garment g where g.id = :id and g.wardrobeId = :wardrobe")
    Optional<Garment> embeddingSnapshot(UUID id, UUID wardrobe);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Garment g where g.wardrobeId = :wardrobe and g.id in :ids order by g.id")
    List<Garment> lockOwned(UUID wardrobe, List<UUID> ids);

    @Query("select g.id from Garment g where g.wardrobeId = :wardrobe order by g.id")
    List<UUID> ownedIds(UUID wardrobe);
}
