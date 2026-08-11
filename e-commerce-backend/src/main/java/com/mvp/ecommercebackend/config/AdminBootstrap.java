package com.mvp.ecommercebackend.config;

import com.mvp.ecommercebackend.auth.entity.Role;
import com.mvp.ecommercebackend.auth.entity.RoleCode;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.auth.entity.UserStatus;
import com.mvp.ecommercebackend.auth.repository.RoleRepository;
import com.mvp.ecommercebackend.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator from {@code ADMIN_EMAIL} / {@code ADMIN_PASSWORD} when both are
 * set, and does nothing otherwise. Idempotent: an existing account with that email is left alone.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final int MINIMUM_ADMIN_PASSWORD_LENGTH = 12;

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminBootstrapProperties properties;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(AdminBootstrapProperties properties,
                          UserRepository userRepository,
                          RoleRepository roleRepository,
                          PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured()) {
            log.info("No ADMIN_EMAIL configured; skipping administrator bootstrap");
            return;
        }
        // Fail fast rather than create a weakly protected administrator.
        if (properties.password() == null
                || properties.password().length() < MINIMUM_ADMIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("ADMIN_PASSWORD must be at least "
                    + MINIMUM_ADMIN_PASSWORD_LENGTH + " characters when ADMIN_EMAIL is set");
        }
        if (userRepository.existsByEmailIgnoreCase(properties.email())) {
            log.info("Administrator {} already exists; nothing to do", properties.email());
            return;
        }

        Role admin = roleRepository.findByCode(RoleCode.ADMIN).orElseThrow(
                () -> new IllegalStateException("ADMIN role is missing; V2 seed did not run"));

        User user = new User();
        user.setEmail(properties.email().trim());
        user.setPasswordHash(passwordEncoder.encode(properties.password()));
        user.setFullName("Administrator");
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(true);
        user.addRole(admin);
        userRepository.save(user);

        log.info("Created initial administrator {}", user.getEmail());
    }
}
