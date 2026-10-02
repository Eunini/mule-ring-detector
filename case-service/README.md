# case-service

Case management for **mule-ring-detector**. The Rust engine pushes scored alerts here; the
service groups them into investigation cases, runs the analyst workflow with four-eyes STR
filing, and produces regulatory and evidence reports.

Java 21, Spring Boot 3.5, Spring Data JPA, Flyway, Spring Security (HTTP Basic),
PostgreSQL at runtime, H2 (PostgreSQL mode) for tests and the `local` profile.

## Run

Local profile (no PostgreSQL needed; H2 file database under `./data/`):

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# or
./mvnw -B package -DskipTests && java -jar target/case-service.jar --spring.profiles.active=local
```

Open <http://localhost:8085/> for the UI. Post the sample alerts as the engine:

```bash
curl -u engine:engine-dev -H 'Content-Type: application/json' \
     --data @src/test/resources/alerts-two-rings.json http://localhost:8085/api/alerts
curl -u analyst1:analyst1-dev http://localhost:8085/api/cases
```

With PostgreSQL (default profile), every credential comes from the environment:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/mrd
export SPRING_DATASOURCE_USERNAME=mrd SPRING_DATASOURCE_PASSWORD=...
export MRD_ANALYST1_PASSWORD=... MRD_ANALYST2_PASSWORD=... MRD_SUPERVISOR1_PASSWORD=... MRD_ENGINE_PASSWORD=...
java -jar target/case-service.jar
```

or `docker compose up --build` (PostgreSQL 16 + the service; dev-only default passwords, override via `.env`).

Tests: `./mvnw -B verify` (unit tests via Surefire, `*IT` integration tests via Failsafe, all on H2 in
PostgreSQL mode with the real Flyway migrations).

## Users (local profile only, dev-only placeholders)

| user        | password          | role       |
|-------------|-------------------|------------|
| analyst1    | `analyst1-dev`    | ANALYST    |
| analyst2    | `analyst2-dev`    | ANALYST    |
| supervisor1 | `supervisor1-dev` | SUPERVISOR (includes ANALYST) |
| engine      | `engine-dev`      | INGEST     |

## API

| Method | Path | Role | Notes |
|---|---|---|---|
| POST | `/api/alerts` | INGEST | JSON array (or single object). Idempotent by `alertId`; returns `received/accepted/duplicates` and the case per alert. 400 on invalid payloads. |
| GET | `/api/cases?status=OPEN&status=ESCALATED&page=0&size=20&sort=updatedAt,desc` | ANALYST | Paged list. |
| GET | `/api/cases/{id}` | ANALYST | Detail: accounts, rings, alerts, allowed transitions, filing request, reports. |
| GET | `/api/cases/{id}/audit` | ANALYST | Append-only audit trail, in order. |
| GET | `/api/cases/{id}/graph` | ANALYST | `{nodes:[{id,label,role,...}], edges:[{source,target,amountUsd,txId,timestamp,detector,laundering,alerted}]}` |
| POST | `/api/cases/{id}/assign` | ANALYST | `{"assignee":"analyst2"}` |
| POST | `/api/cases/{id}/transition` | ANALYST | `{"to":"CLOSED","disposition":"FALSE_POSITIVE","comment":"..."}` |
| POST | `/api/cases/{id}/comments` | ANALYST | `{"text":"..."}` |
| GET | `/api/cases/{id}/str.xml` | ANALYST | goAML-style STR; stores report metadata (SHA-256) and audits it. |
| GET | `/api/cases/{id}/summary.html` | ANALYST | Printable summary with inline SVG evidence graph. |
| GET | `/api/cases/{id}/summary.pdf` | ANALYST | Same summary as PDF (recorded + audited). |
| POST | `/api/cases/{id}/filing-request` | ANALYST | Case must be ESCALATED with an STR generated. |
| POST | `/api/cases/{id}/filing-approval` | SUPERVISOR | Must differ from the requester; closes with `STR_FILED`. |
| POST | `/api/cases/{id}/filing-rejection` | SUPERVISOR | `{"reason":"..."}`; must differ from the requester; sends case back to INVESTIGATING. |
| GET | `/api/me` | ANALYST | Signed-in user and roles (used by the UI). |
| GET | `/actuator/health` | public | |

Errors are RFC 7807 problem documents: 400 validation, 403 role or four-eyes violation, 404 unknown
case, 409 illegal transition or workflow precondition.

## Case grouping

An accepted alert joins an active case (OPEN, INVESTIGATING, ESCALATED) that already contains any of
its accounts (`fromAccount`, `toAccount`, `ringAccounts`) or an alert with the same `ringId`. If it
overlaps several active cases they are merged into the oldest one; the others become `MERGED`
(pointing at the survivor) and both sides get a `MERGED` audit event. Closed cases are never
reopened: a new alert on a closed case's accounts opens a new case. Grouping is serialised inside
one instance; running several instances would need a database lock (for example a PostgreSQL
advisory lock) around ingestion.

Priority is derived from maximum score, alert count and total flagged USD (`Priority.of`).

## Workflow

```
OPEN -> INVESTIGATING -> ESCALATED -> CLOSED (STR_FILED, via four-eyes approval only)
            |      ^          |
            |      +----------+  send back
            +-> CLOSED (FALSE_POSITIVE | NO_FURTHER_ACTION)
ESCALATED -> CLOSED (FALSE_POSITIVE | NO_FURTHER_ACTION) is also allowed.
```

Four-eyes filing: analyst A generates the STR (`str.xml`) and raises a filing request on an
ESCALATED case; a supervisor B (B != A) approves (case CLOSED / STR_FILED) or rejects (case back to
INVESTIGATING). While a request is pending, manual transitions return 409. Every action writes an
`audit_event` row (actor, action, from/to status, details, timestamp); the entity is immutable, its
repository has no update or delete operations, and JPA removal is rejected.

## STR report

> Generic goAML-style structure for demonstration; not certified against any FIU schema.

goAML is the UNODC system used by financial intelligence units in many countries. The XML uses goAML element names (`report`, `rentity_id`, `submission_code`, `report_code`,
`entity_reference`, `reporting_person`, `location`, `reason`, `action`, `transaction` with
`t_from`/`t_to` accounts, `report_indicators`) and validates against this project's own
`src/main/resources/str/str-subset.xsd`. Transmission-mode, funds and indicator codes are
project-specific demonstration values:

| detector | indicator |
|---|---|
| fan_out | MRD-FANOUT |
| fan_in | MRD-FANIN |
| cycle | MRD-CYCLE |
| scatter_gather | MRD-SCATGATH |
| gather_scatter | MRD-GATHSCAT |
| pass_through | MRD-PASSTHRU |
| velocity | MRD-VELOCITY |
| ring | MRD-RING |
| (always) model score above threshold | MRD-MODEL |
| unknown detector | MRD-OTHER |

Reporting-entity details (`mrd.str.*`) are configurable. Amounts are reported in USD as sent by the
engine (`amountUsd`); the original currency is kept in each transaction's `comments`.
