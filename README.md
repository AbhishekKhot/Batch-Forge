# BatchForge

**BatchForge is a production-grade backend for ingesting large CSV files asynchronously — engineered for correctness under failure.**

It is a Spring Boot service that turns a bulk contact import into a first-class, observable job. Clients upload a CSV directly to object storage through a time-limited presigned URL — bytes never flow through the application tier — and a RabbitMQ-driven worker validates and persists rows in checkpointed batches. Valid rows are imported, invalid rows are captured in a downloadable error report, and progress is queryable at every moment.

What sets it apart is its behavior at the edges:

- **Crash-safe by design.** Work is committed in idempotent, checkpointed batches, so a worker that dies mid-file resumes exactly where it left off — no duplicated rows, no lost progress.
- **Exactly-once effects over at-least-once delivery.** A claim gate and `AFTER_COMMIT` publishing eliminate the classic lost-update and phantom-message races that plague naive queue consumers.
- **Multi-tenant from the ground up.** Every user belongs to an organization, and tenant isolation is enforced at the query layer — never trusting a client-supplied identifier.
- **Fails loudly, recovers gracefully.** Exhausted retries dead-letter to a DLQ and mark the job failed; the error report is derived from durable state, so it always reconciles with the data that was actually written.

In short: the boring parts (upload, parse, store) are asynchronous and fast; the hard parts (idempotency, resumability, tenancy, back-pressure) are treated as the actual product.

---

## How it works (high-level design)

```mermaid
flowchart TB
    Client([API Client])

    subgraph App["BatchForge Application (Spring Boot)"]
        API["REST API<br/>auth · jobs"]
        Worker["Worker<br/>in-process consumer"]
    end

    subgraph Infra["Backing Services"]
        PG[("PostgreSQL<br/>jobs · records · users")]
        MQ{{"RabbitMQ<br/>import queue + DLQ"}}
        Redis[("Redis<br/>tokens + job cache")]
        MinIO[("MinIO<br/>CSV files + error reports")]
    end

    Client -->|"1 · register / login → token"| API
    Client -->|"2 · create job → upload link"| API
    Client -->|"3 · upload CSV file"| MinIO
    Client -->|"4 · confirm upload"| API
    API   -->|"publish job"| MQ
    MQ    -->|"deliver"| Worker
    Worker -->|"read CSV"| MinIO
    Worker -->|"write rows + progress"| PG
    Worker -->|"write error report"| MinIO
    API   -->|"read / write"| PG
    API   -->|"tokens + cached status"| Redis
    Client -->|"5 · poll status / get result"| API

    classDef client fill:#dbeafe,stroke:#1e40af,color:#1e3a8a,stroke-width:1px;
    classDef app fill:#dcfce7,stroke:#166534,color:#14532d,stroke-width:1px;
    classDef infra fill:#fef3c7,stroke:#92400e,color:#78350f,stroke-width:1px;

    class Client client;
    class API,Worker app;
    class PG,MQ,Redis,MinIO infra;
```

**The lifecycle of an import**

Every job moves through an explicit state machine — `PENDING → QUEUED → PROCESSING → COMPLETED | FAILED` — and each transition is durable and recoverable:

1. **Authenticate.** Register an organization (the first user becomes its owner) or log in to receive a stateless JWT scoped to that tenant.
2. **Create the job** (`PENDING`). The API mints a job record and hands back a job ID plus a short-lived presigned upload URL. No file has moved yet.
3. **Upload direct-to-storage.** The client streams the CSV straight to object storage using the presigned URL — the application never buffers the payload, so file size is bounded by storage, not memory.
4. **Confirm** (`QUEUED`). The API verifies the object landed in storage, commits the state change, and — only *after commit* — publishes the job to RabbitMQ. This ordering is deliberate: a message is never emitted for work that isn't durably persisted.
5. **Process** (`PROCESSING`). The worker claims the job, streams the CSV, and writes rows in ~500-row batches. Each batch commits the imported rows, the error rows, and the progress cursor in a single transaction, so progress advances only as data lands.
6. **Complete & retrieve** (`COMPLETED`). Poll the job to watch live progress; on completion, fetch the result and — if any rows failed validation — a download link for the error report, rendered from the durable error rows.

If the worker crashes at step 5, redelivery re-claims the job, reads the last committed cursor, and skips everything already imported. If processing exhausts its retries, the message dead-letters and the job is marked `FAILED`. Either way, the system converges to a consistent, explainable state.

---

## Tech stack

| Area | Technology |
|------|-----------|
| Language / runtime | Java 21 |
| Framework | Spring Boot 4.1 |
| Build | Maven (via the included `mvnw` wrapper) |
| Database | PostgreSQL 16 |
| Message queue | RabbitMQ 4 |
| Cache / tokens | Redis 7 |
| Object storage | MinIO (S3-compatible) |
| API docs | OpenAPI / Swagger UI |

---

## Prerequisites

- **Docker** and **Docker Compose**.
- **Java 21** and the `mvnw` wrapper — needed to build and run the app from source, or to run the test suite.

---

## Quick start

Bring up the entire stack — the application plus PostgreSQL, RabbitMQ, Redis, and MinIO —
with a single command. Nothing to install beyond Docker:

```bash
docker compose up --build
```

Wait for the log line `Started BatchforgeApplication`. That's it — BatchForge is running.

| What | URL |
|------|-----|
| API base | http://localhost:8080 |
| **Swagger UI** (try the API in your browser) | http://localhost:8080/swagger-ui/index.html |
| Health check | http://localhost:8080/actuator/health |
| RabbitMQ management console | http://localhost:15672 |
| MinIO console | http://localhost:9001 |

Default username/password for the consoles is `batchforge` / `batchforge` (dev only).

To stop: `Ctrl-C`, then `docker compose down` (add `-v` to also wipe the stored data).

---

## End-to-end API walkthrough

The friendliest way is **Swagger UI** (http://localhost:8080/swagger-ui/index.html) — you can
fill in and send every request from the browser. If you prefer the command line, here's the
full flow with `curl`:

```bash
# 1. Register an organization + first user (you get back an access token)
curl -s -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"organizationName":"Acme","email":"you@acme.com","password":"s3cret-password"}'

# Save the access token from the response, then:
TOKEN="paste-access-token-here"

# 2. Create an import job (returns a jobId and an uploadUrl)
curl -s -X POST http://localhost:8080/jobs -H "Authorization: Bearer $TOKEN"

# 3. Upload your CSV to the uploadUrl from step 2
curl -X PUT --upload-file contacts.csv "paste-uploadUrl-here"

# 4. Confirm the upload — the job is now queued for processing
curl -s -X POST http://localhost:8080/jobs/<jobId>/uploaded -H "Authorization: Bearer $TOKEN"

# 5. Check status / progress (repeat until status is COMPLETED)
curl -s http://localhost:8080/jobs/<jobId> -H "Authorization: Bearer $TOKEN"

# 6. Get the result (a download link for the error report, if any rows failed)
curl -s http://localhost:8080/jobs/<jobId>/result -H "Authorization: Bearer $TOKEN"
```

### CSV format

BatchForge imports **contacts**. Your file must have this header row:

```csv
email,first_name,last_name,phone
alice@example.com,Alice,Anderson,+1-555-0100
bob@example.com,Bob,Brown,
```

- `email` — required, must be a valid email address
- `first_name` — required
- `last_name` — required
- `phone` — optional

Rows that fail these rules are skipped, counted as failures, and listed in the downloadable
error report; valid rows are imported. A job with some bad rows still completes successfully.

---

## Running the tests

The test suite runs the application against **real** Postgres, Redis, and MinIO instances.
It uses Testcontainers, which starts these dependencies automatically in throwaway
containers for the duration of the run — you do **not** need to start the compose stack
yourself.

1. Make sure Docker is running (Docker Desktop open, or the Docker daemon started).
2. Run the suite:

```bash
./mvnw test
```

Testcontainers will spin up the databases it needs, run all tests against them, and tear
them down when finished.

After the run, a code-coverage report is generated at:

```
target/site/jacoco/index.html
```

The build enforces a minimum line-coverage threshold, so a drop in coverage fails the build.

---

## Project layout

```
batchforge/
├── src/main/java/com/batchforge/
│   ├── auth/           # registration, login, JWT, security
│   ├── job/            # jobs, CSV parsing, the worker, caching
│   ├── storage/        # MinIO integration (presigned URLs, uploads)
│   ├── organization/   # organizations (tenants)
│   ├── user/           # users & roles
│   ├── ratelimit/      # request rate limiting
│   ├── observability/  # correlation-ID logging
│   └── common/         # shared error handling
├── src/main/resources/
│   ├── application.yml         # base (dev) configuration
│   ├── application-prod.yml    # production overrides
│   └── db/migration/           # Flyway database migrations
├── Dockerfile
├── docker-compose.yml
└── .env.example
```