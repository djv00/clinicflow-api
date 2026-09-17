package com.jiangyudai.clinicflow.security.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.springframework.util.Assert;

import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "user_accounts", uniqueConstraints =
        @UniqueConstraint(name = "uk_user_accounts_username_key", columnNames = "username_key"))
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 100)
    private String username;

    @Column(name = "username_key", nullable = false, updatable = false, length = 300)
    private String usernameKey;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountRole role;

    @Column(nullable = false)
    private boolean enabled;

    protected UserAccount() {
    }

    public UserAccount(String username, String passwordHash, AccountRole role) {
        validateUsername(username);
        Assert.hasText(passwordHash, "Password hash is required");
        Assert.notNull(role, "Account role is required");
        this.username = username;
        this.usernameKey = usernameKey(username);
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = true;
    }

    public static void validateUsername(String username) {
        Assert.hasText(username, "Account username is required");
        Assert.isTrue(username.length() <= 100,
                "Account username must not exceed 100 characters for business audit records");
    }

    public static String usernameKey(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public AccountRole getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
