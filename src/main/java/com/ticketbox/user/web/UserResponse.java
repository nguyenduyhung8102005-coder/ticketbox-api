package com.ticketbox.user.web;

import com.ticketbox.user.domain.Role;
import com.ticketbox.user.domain.User;

public record UserResponse(Long id, String email, String phone, String fullName, Role role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getPhone(), user.getFullName(), user.getRole());
    }
}
