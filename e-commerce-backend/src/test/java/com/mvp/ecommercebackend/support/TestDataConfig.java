package com.mvp.ecommercebackend.support;

import com.mvp.ecommercebackend.auth.repository.RoleRepository;
import com.mvp.ecommercebackend.auth.repository.UserRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;

@TestConfiguration
public class TestDataConfig {

    @Bean
    public TestDataFactory testDataFactory(UserRepository userRepository,
                                            RoleRepository roleRepository,
                                            PasswordEncoder passwordEncoder) {
        return new TestDataFactory(userRepository, roleRepository, passwordEncoder);
    }
}
