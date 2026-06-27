package com.company.pos.configuration.infrastructure;

import com.company.pos.configuration.domain.Setting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingRepository extends JpaRepository<Setting, String> {
}
