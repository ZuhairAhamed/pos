package com.company.pos.menu.infrastructure;

import com.company.pos.menu.domain.ModifierOption;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModifierOptionRepository extends JpaRepository<ModifierOption, UUID> {

    List<ModifierOption> findByGroupId(UUID groupId);
}
