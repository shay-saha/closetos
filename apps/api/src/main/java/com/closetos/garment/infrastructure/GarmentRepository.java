package com.closetos.garment.infrastructure;

import com.closetos.garment.domain.Garment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GarmentRepository extends JpaRepository<Garment, UUID> {
    Optional<Garment> findByIdAndWardrobeId(UUID id, UUID wardrobeId);
}
