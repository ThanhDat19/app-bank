# BL-001 — Bootstrap Backend + Swagger + Workflow

**Type:** Chore / platform enablement. **Priority:** P0. **Status:** Ready for human review.

## User story
As a backend developer, I need a reproducible project with local PostgreSQL, migration tooling, Swagger/OpenAPI and CI so every banking feature can be developed and reviewed independently.

## Acceptance criteria
1. Java 21 and Maven can build the project.
2. `docker compose up -d postgres` starts healthy PostgreSQL at localhost:5433.
3. Spring Boot starts using environment-based DB credentials.
4. `/actuator/health` reports UP; `/v3/api-docs` is valid JSON; `/swagger-ui.html` loads locally.
5. `/api/v1/system/info` returns `name=bank-platform`, `mode=SIMULATION`.
6. Pull Request template and CI checks are committed.
7. No real ledger money-posting endpoint exists yet.

## Reviewer instructions
- Check dependency versions against the Java runtime and verify application boot.
- Inspect OpenAPI UI; ensure no privileged financial action is exposed by Swagger.
- Check branch policy: owner reviews and merges, contributor never pushes directly to `main`.

## Limitations of this delivered draft
The provided environment has Java 21 and Git, but **Maven and Docker are not installed**. Consequently `mvn verify`, app startup, Compose health and Swagger runtime checks are **not verified here**. CI and local reviewer must run them before approval.
