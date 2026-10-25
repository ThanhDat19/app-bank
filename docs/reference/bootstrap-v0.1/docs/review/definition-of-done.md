# Definition of Done (DoD)

Each PR MUST include:
- [ ] Linked issue with scope and explicit exclusions.
- [ ] Business rules / acceptance criteria (Given / When / Then).
- [ ] Implementation with clear module boundaries and no plaintext secrets.
- [ ] Unit/integration tests, plus concurrency/idempotency/fault tests when financial state is affected.
- [ ] Flyway migration and rollback/compatibility explanation if data model changes.
- [ ] Updated Swagger/OpenAPI endpoint descriptions and error responses for REST API changes.
- [ ] Evidence: commands executed, CI link, screenshot/log excerpt with no secrets.
- [ ] Security and authorization review; no personal banking data in fixture/logs.
- [ ] Explanation of what failed / remains unverified.
- [ ] Owner approval before merge; no force-push to protected main.

A green build without financial correctness tests is not sufficient to mark a transfer feature Done.
