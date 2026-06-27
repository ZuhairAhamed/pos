package com.company.pos.auth.domain;

import com.company.pos.auth.api.Role;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Converter
public class RoleSetConverter implements AttributeConverter<Set<Role>, String> {

    @Override
    public String convertToDatabaseColumn(Set<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            return "";
        }
        return roles.stream().map(Role::name).collect(Collectors.joining(","));
    }

    @Override
    public Set<Role> convertToEntityAttribute(String dbValue) {
        if (dbValue == null || dbValue.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(dbValue.split(","))
                .map(String::trim)
                .map(Role::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
