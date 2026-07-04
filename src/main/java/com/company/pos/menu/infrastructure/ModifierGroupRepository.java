package com.company.pos.menu.infrastructure;

import com.company.pos.menu.domain.ModifierGroup;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModifierGroupRepository extends JpaRepository<ModifierGroup, UUID> {
}
