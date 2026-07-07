package com.company.pos.kitchen.infrastructure;

import com.company.pos.kitchen.domain.StationAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StationAssignmentRepository extends JpaRepository<StationAssignment, String> {
}
