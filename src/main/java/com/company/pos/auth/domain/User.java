package com.company.pos.auth.domain;

import com.company.pos.auth.api.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "app_user")
public class User {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "cashier_code", unique = true, length = 20)
    private String cashierCode;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "pin_hash", length = 100)
    private String pinHash;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Convert(converter = RoleSetConverter.class)
    @Column(name = "roles", nullable = false, length = 200)
    private Set<Role> roles = new LinkedHashSet<>();

    @Column(nullable = false)
    private boolean enabled = true;

    protected User() {
        // JPA
    }

    public User(UUID id, String username, String displayName, String passwordHash, Set<Role> roles) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.roles = new LinkedHashSet<>(roles);
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getCashierCode() {
        return cashierCode;
    }

    public void setCashierCode(String cashierCode) {
        this.cashierCode = cashierCode;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getPinHash() {
        return pinHash;
    }

    public void setPinHash(String pinHash) {
        this.pinHash = pinHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
