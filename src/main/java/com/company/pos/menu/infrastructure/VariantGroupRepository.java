package com.company.pos.menu.infrastructure;

import com.company.pos.menu.domain.VariantGroup;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VariantGroupRepository extends JpaRepository<VariantGroup, UUID> {
}
