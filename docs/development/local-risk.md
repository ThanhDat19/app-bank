# BL-017 — Run and verify local risk

Build on Java21: `./mvnw -B -ntp clean verify`. Protobuf Java/stubs are generated from the committed proto using
Maven Central tools and the detected OS classifier. The first build needs network access for gRPC/Protobuf/protoc;
subsequent cached local builds can use the existing offline `.m2-local` command.

Artifacts:

- `target/core-banking-service-0.1.0-SNAPSHOT.jar`: existing Spring core, risk disabled by default.
- `target/core-banking-service-0.1.0-SNAPSHOT-risk-simulator.jar`: standalone loopback simulator, no DB/IAM required.

Start the simulator in a separate local terminal:

```powershell
$env:RISK_SIMULATOR_PORT = '9091'
$env:RISK_SIMULATOR_MAX_AMOUNT = '100000000'
java -jar target/core-banking-service-0.1.0-SNAPSHOT-risk-simulator.jar
```

It binds only127.0.0.1. Its policy version includes the cap. Stop the process with Ctrl+C; no database state exists.
Use only synthetic amounts/accounts. Do not expose this plaintext local trust boundary to another machine.

For an intentionally run local core, retain its existing DB/IAM configuration, activate the local profile and set:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:RISK_ENABLED = 'true'
$env:RISK_HOST = '127.0.0.1'
$env:RISK_PORT = '9091'
$env:RISK_DEADLINE_MILLIS = '750'
$env:RISK_APPROVAL_MILLIS = '5000'
java -jar target/core-banking-service-0.1.0-SNAPSHOT.jar
```

The core would apply V14 on startup to its configured DB. Automated task verification did **not** start this
process against bank_lab or migrate local persistent data; only disposable PostgreSQL/Kafka tests were used.
Do not treat these manual instructions as evidence that a local persistent database was upgraded.

Use the existing authenticated Swagger transfer workflow: amount<=cap can proceed if final financial checks pass;
amount>cap rejects422; stopped/unavailable simulator fails503; same-key committed replay succeeds during outage.
Read `/api/v1/transfers/{id}/risk-decision` as the sender. A disabled new transfer records BYPASSED, not a fake approval.
Cold connection setup can consume the RPC deadline; fail-closed503 is retryable with the original key after backoff.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -o -B -ntp '-Dmaven.repo.local=D:\App\app-bank\.m2-local' '-Dspring.test.context.cache.maxSize=1' clean verify
```

Focused checks: add `'-Dit.test=RiskTransferIT,RiskMigrationIT,RiskSimulatorIT,TransferMigrationIT'`.
RiskSimulatorIT actually launches the classifier JAR, removes DB/IAM env settings and calls its generated unary
stub over TCP; the temporary process is stopped in finally. No browser/proxy/remote TLS or cloud verification.
