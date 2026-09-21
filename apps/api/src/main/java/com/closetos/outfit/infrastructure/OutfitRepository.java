package com.closetos.outfit.infrastructure;

import com.closetos.outfit.domain.Outfit;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface OutfitRepository extends JpaRepository<Outfit, UUID> {
    Optional<Outfit> findByIdAndWardrobeId(UUID id, UUID wardrobeId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select o from Outfit o where o.id = :id and o.wardrobeId = :wardrobe")
    Optional<Outfit> lockForWear(UUID id, UUID wardrobe);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Outfit o where o.id = :id and o.wardrobeId = :wardrobe")
    Optional<Outfit> lockForUpdate(UUID id, UUID wardrobe);
}
