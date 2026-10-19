package com.ticketbox.user;

import com.ticketbox.user.domain.Role;
import com.ticketbox.user.domain.User;
import com.ticketbox.user.domain.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Tạo tài khoản ADMIN đầu tiên khi khởi động (API đăng ký chỉ tạo CUSTOMER).
 * Chỉ chạy khi có cấu hình app.bootstrap.admin.email; đã tồn tại thì bỏ qua.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.bootstrap.admin", name = "email")
public class AdminAccountInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;
    private final String fullName;

    public AdminAccountInitializer(UserRepository userRepository,
                                   PasswordEncoder passwordEncoder,
                                   @Value("${app.bootstrap.admin.email}") String email,
                                   @Value("${app.bootstrap.admin.password:}") String password,
                                   @Value("${app.bootstrap.admin.full-name:Quản trị viên}") String fullName) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email.trim().toLowerCase(Locale.ROOT);
        this.password = password;
        this.fullName = fullName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || userRepository.existsByEmail(email)) {
            return;
        }
        if (password == null || password.length() < 8) {
            log.warn("Bỏ qua tạo admin: app.bootstrap.admin.password phải có ít nhất 8 ký tự");
            return;
        }
        userRepository.save(User.create(email, null, passwordEncoder.encode(password), fullName, Role.ADMIN));
        log.info("Đã tạo tài khoản admin {}", email);
    }
}
