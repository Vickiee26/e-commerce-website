# ShopFlow Backend

Spring Boot 4.1 / Java 17 REST API. Flyway owns the schema; Hibernate runs with
`ddl-auto=validate` in every profile, so a mapping that drifts from a migration fails at boot.

## Prerequisites

- JDK 17
- A Docker-compatible container runtime (Docker Desktop, Colima, or Podman) — used for the local
  database and for the Testcontainers-backed integration tests.

## Local setup

```bash
cp .env.example .env      # then replace every CHANGE_ME placeholder
docker compose up -d      # starts Postgres 16 on ${DB_PORT}
./mvnw spring-boot:run    # defaults to the dev profile
```

`.env` is gitignored and is the only place local credentials live. The dev profile imports it via
`spring.config.import=optional:file:.env[.properties]`; no property has a hard-coded default, so a
missing value fails fast instead of silently starting with a weak one. `JWT_SECRET` must be at
least 32 characters (HS256) — generate one with `openssl rand -base64 48`.

The `prod` profile reads the same names from the real environment and imports nothing.

## Tests

```bash
./mvnw verify
```

Surefire runs the unit tests (`*Test`); Failsafe runs the integration tests (`*IT`), which start a
throwaway `postgres:16-alpine` container via Testcontainers. One container is shared by the whole
suite; isolation comes from truncating tables between tests.

### Colima users

Testcontainers looks for Docker at `/var/run/docker.sock` and assumes mapped ports are reachable on
host `localhost`. Neither holds under Colima: the socket lives under `~/.colima`, and the VM has its
own IP. Export these before `./mvnw verify` (put them in your shell profile to make it permanent):

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export TESTCONTAINERS_HOST_OVERRIDE="$(colima status 2>&1 | sed -n 's/.*address: \([0-9.]*\).*/\1/p')"
```

Without the first two, the build fails with `Could not find a valid Docker environment`. Without the
third, the container starts but JDBC fails with `Connection to localhost:<port> refused`.

Docker Desktop needs none of this.

## Documentation

- Requirements: `../docs/superpowers/specs/`
- Implementation plan: `../docs/superpowers/plans/`
- API docs (running app): `/swagger-ui`
