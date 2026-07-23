package com.company.pos.menu.infrastructure;

import com.company.pos.menu.domain.ModifierGroupAssignment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModifierGroupAssignmentRepository extends JpaRepository<ModifierGroupAssignment, UUID> {

    List<ModifierGroupAssignment> findBySku(String sku);

    boolean existsByGroupIdAndSku(UUID groupId, String sku);

    void deleteByGroupIdAndSku(UUID groupId, String sku);

    List<ModifierGroupAssignment> findByGroupId(UUID groupId);
}
