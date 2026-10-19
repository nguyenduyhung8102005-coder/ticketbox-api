package com.ticketbox.user.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Viết lower(...) để PostgreSQL dùng được unique index uk_users_email_lower. */
    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmail(@Param("email") String email);

    @Query("select count(u) from User u where lower(u.email) = lower(:email)")
    long countByEmailIgnoringCase(@Param("email") String email);

    default boolean existsByEmail(String email) {
        return countByEmailIgnoringCase(email) > 0;
    }

    boolean existsByPhone(String phone);
}
