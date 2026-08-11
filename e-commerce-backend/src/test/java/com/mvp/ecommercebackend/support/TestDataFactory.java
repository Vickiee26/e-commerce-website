package com.mvp.ecommercebackend.support;

import com.mvp.ecommercebackend.auth.entity.Role;
import com.mvp.ecommercebackend.auth.entity.RoleCode;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.auth.entity.UserStatus;
import com.mvp.ecommercebackend.auth.repository.RoleRepository;
import com.mvp.ecommercebackend.auth.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Builds committed test fixtures. Passwords are hashed with the real encoder. */
public class TestDataFactory {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public TestDataFactory(UserRepository userRepository, RoleRepository roleRepository,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public User createCustomer(String email, String rawPassword) {
        return createCustomer(email, rawPassword, UserStatus.ACTIVE);
    }

    public User createCustomer(String email, String rawPassword, UserStatus status) {
        return create(email, rawPassword, status, RoleCode.CUSTOMER);
    }

    public User createAdmin(String email, String rawPassword) {
        return create(email, rawPassword, UserStatus.ACTIVE, RoleCode.ADMIN);
    }

    private User create(String email, String rawPassword, UserStatus status, RoleCode roleCode) {
        Role role = roleRepository.findByCode(roleCode).orElseThrow(
                () -> new IllegalStateException("Role " + roleCode + " was not seeded"));

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setFullName("Test " + roleCode);
        user.setStatus(status);
        user.addRole(role);
        return userRepository.saveAndFlush(user);
    }
}
