package com.mvp.ecommercebackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional first-admin credentials, supplied by environment variables only.
 *
 * <p>The design doc puts the initial admin in the V2 migration, which would mean committing a
 * password hash. Creating it from the environment at startup keeps the repository free of
 * credentials and lets each environment have a different admin.
 */
@ConfigurationProperties(prefix = "app.bootstrap.admin")
public record AdminBootstrapProperties(String email, String password) {

    public boolean isConfigured() {
        return email != null && !email.isBlank();
    }
}
