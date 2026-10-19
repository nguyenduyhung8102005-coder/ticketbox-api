package com.ticketbox.user.domain;

import com.ticketbox.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status;

    public static User create(String email, String phone, String passwordHash, String fullName, Role role) {
        User user = new User();
        user.email = email;
        user.phone = phone;
        user.passwordHash = passwordHash;
        user.fullName = fullName;
        user.role = role;
        user.status = UserStatus.ACTIVE;
        return user;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
