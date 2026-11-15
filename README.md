# OpenOps

A runnable API foundation for typed datasets, deterministic queries, private conversations and saved results.

## Run

Requires Java 25 and Docker Compose. Spring Boot 4.1.2 is pinned in `pom.xml`; the Maven wrapper pins Maven 3.9.16. Generate local credentials in your shell:

```bash
export API_TOKEN="$(openssl rand -hex 32)"
export TOKENS="{\"$API_TOKEN\":{\"tenant\":\"local\",\"user\":\"1\",\"role\":\"admin\"}}"
docker compose up --build -d --wait
```

The API listens at **http://localhost:8096**. `/healthz` is public. There is no browser UI in this first foundation. The named `data` volume retains records when containers are replaced; `docker compose down` preserves it. Keep credentials to reuse them after a shell restart. Changing the token configuration requires recreating the container; identities and tenant identifiers determine access to existing data.

For local development, point `JAVA_HOME` at a Java 25 JDK:

```bash
./mvnw clean verify
PORT=8096 java -jar target/openops-0.2.0-SNAPSHOT.jar
```

`DATABASE` defaults to `openops.db` locally and `/data/openops.db` in Docker. Tokens contain at least 24 characters. `TOKENS` is a JSON map from credential to `{"tenant", "user", "role"}`; roles are `admin`, `editor`, `viewer`, `end_user`. See [API scope](docs/api.md) for the product-specific access policy. Never reuse the synthetic credentials in tests.

## Example

```bash
curl -H "Authorization: Bearer $API_TOKEN" -H 'Content-Type: application/json' \
  http://localhost:8096/api/v1/datasets \
  -d '{"name":"Readings","columns":{"value":"integer"},"csv":"value\n2\n5\n"}'
```

## Verify

```bash
sh scripts/verify.sh
```

This runs JUnit acceptance tests through a real Spring Boot HTTP server, checks database persistence and rollback, and requires exactly **100% executable-line coverage** across every production Java class. JaCoCo HTML/XML reports are in `target/site/jacoco/`; Surefire results are in `target/surefire-reports/`. There are no coverage exclusions. Method and branch counts are reported separately in [validation](docs/validation.md). CI runs the same clean build.

See [validation](docs/validation.md), [architecture](docs/architecture.md), [API scope](docs/api.md), and the [capability ledger](docs/compatibility.md).

## Remaining work

The ledger records the next capabilities and known limitations. These foundations use local token provisioning and SQLite. OAuth/SSO, schema upgrade migrations, source and column access policies, rate limiting and distributed operation remain future work. Licensed under the [Apache License 2.0](LICENSE); see [notices](THIRD_PARTY_NOTICES.md). No customer data or paid service is required for development or tests.

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, validation and contribution terms.

## Product experience

See the [professional appearance and dark-mode requirements](docs/product-experience.md), including current implementation status and browser-release acceptance criteria.
