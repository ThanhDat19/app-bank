# Local setup (Windows / IntelliJ)

Prerequisites: JDK 21, Git, Maven 3.9+, Docker Desktop with Linux containers.

1. Copy `.env.example` to `.env`; change the local example password. Do not commit `.env`.
2. In PowerShell, load environment variables from `.env` **or** configure `DB_USER` / `DB_PASSWORD` in IntelliJ Run Configuration. Docker Compose reads `.env` automatically, but the Java process does not.
3. Start PostgreSQL: `docker compose up -d postgres`.
4. Verify: `docker compose ps` and `docker compose logs postgres`.
5. Run: `mvn clean test` and `mvn spring-boot:run` with DB variables set.
6. Verify: `http://localhost:8080/actuator/health` returns UP; `http://localhost:8080/v3/api-docs` returns OpenAPI JSON; `http://localhost:8080/swagger-ui.html` loads Swagger UI.
7. Verify sample endpoint: `http://localhost:8080/api/v1/system/info`.

**PowerShell sample**:

```powershell
Copy-Item .env.example .env
# Edit .env and change its values first
Get-Content .env | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
    $name,$value = $_ -split '=',2
    [Environment]::SetEnvironmentVariable($name,$value,'Process')
}
docker compose up -d postgres
mvn clean test
mvn spring-boot:run
```

*Security:* local only. Change passwords before actual use. Swagger must not be publicly exposed when real auth/financial APIs exist.
