package com.company.pos.menu.infrastructure;

import com.company.pos.menu.domain.VariantMember;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VariantMemberRepository extends JpaRepository<VariantMember, UUID> {

    List<VariantMember> findByVariantGroupId(UUID variantGroupId);
}
