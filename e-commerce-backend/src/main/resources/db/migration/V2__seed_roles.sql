-- Roles only. The first admin user is created at boot by AdminBootstrap from
-- ADMIN_EMAIL / ADMIN_PASSWORD, because seeding it here would require committing
-- a real password hash.
INSERT INTO roles (id, code, name, created_at, updated_at)
VALUES (gen_random_uuid(), 'CUSTOMER', 'Customer', now(), now()),
       (gen_random_uuid(), 'ADMIN', 'Administrator', now(), now());
