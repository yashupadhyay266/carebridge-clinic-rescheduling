# ADR: CareBridge Clinic Rescheduling Engine

- **Status:** Accepted
- **Date:** 2026-11-03
- **Decision owners:** CareBridge engineering team
- **Scope:** Provider-offline rescheduling, safe recommendation generation, booking/cancellation consistency, and LLM-assisted explanations

## 1. Context

CareBridge must help a clinic respond when a provider becomes unavailable. The system has to identify safe replacement appointments without double-booking a slot, preserve the original appointment duration, respect provider availability and specialty, and let an authorized workflow accept or reject the proposed change.

The application also uses an OpenAI-compatible LLM gateway to improve the human-readable explanation shown with a recommendation. The LLM is an untrusted explanation component: it must not decide which provider or slot is selected, calculate the confidence score, book an appointment, or modify appointment state.

The critical consistency risks are:

1. Two requests attempting to book the same slot concurrently.
2. A retry creating duplicate appointments or duplicate side effects.
3. A recommendation becoming stale before a patient or staff member accepts it.
4. A failed transaction leaving a slot, appointment, or idempotency record partially updated.
5. An active appointment being created for a slot that already has another active appointment.

## 2. Decision drivers

- Scheduling decisions must be deterministic and explainable.
- The LLM must be unable to invent or override operational facts.
- Booking and rescheduling must be safe under concurrent PostgreSQL transactions.
- Retried requests must be idempotent.
- The system must remain usable when the LLM gateway is unavailable or unconfigured.
- Patient data and credentials must not be unnecessarily sent to logs or committed to source control.
- The demo must be reproducible from the public repository using PostgreSQL and the documented seed data.

## 3. Decision

### 3.1 Deterministic scheduling engine owns all operational decisions

`SchedulingService` owns provider and slot selection. It selects only candidates that satisfy the following rules:

1. The slot is currently `OPEN`.
2. The candidate provider is not the unavailable provider.
3. The candidate provider is not `OFFLINE`.
4. The candidate slot duration matches the affected appointment duration.
5. A same-specialty candidate is preferred when available.
6. Candidates are ordered by start time, producing a deterministic earliest safe opening.

The service calculates the confidence score from deterministic scheduling facts. The LLM receives the already-selected provider, slot, trigger, and contextual facts only after this selection is complete.

### 3.2 LLM is explanation-only

`LlmRecommendationClient` calls the configured OpenAI-compatible gateway using:

- `LLM_BASE_URL`
- `LLM_API_KEY`
- `LLM_MODEL`
- `LLM_PRODUCT`

The prompt instructs the model to return a short JSON explanation and prohibits invented providers, times, availability, or medical facts. The response is parsed and validated before it is stored.

The LLM cannot:

- select a provider;
- select a slot;
- calculate confidence;
- book or cancel an appointment;
- accept or reject a recommendation;
- change appointment, provider, slot, or suggestion state.

If the key is missing, the gateway fails, or the response is malformed, the service stores a deterministic `RULE_BASED_FALLBACK` explanation. Successful explanations are stored with source `LLM`. The UI labels these sources as `AI explanation` and `Scheduling explanation` respectively.

### 3.3 PostgreSQL is the consistency boundary

The application uses PostgreSQL with Flyway migrations. State-changing service methods are transactional. Repository methods use `PESSIMISTIC_WRITE` locks for slots, appointments, and suggestions when a workflow needs to read and mutate the same record.

The database also enforces invariants that must not depend only on Java code:

- A unique idempotency key/actor/operation combination.
- A partial unique index allowing only one active appointment for a slot:

```sql
CREATE UNIQUE INDEX one_active_appointment_per_slot
    ON appointments(slot_id)
    WHERE status IN ('CONFIRMED', 'RESCHEDULE_PENDING', 'RESCHEDULED');
```

This defense-in-depth approach means the application prevents conflicts optimistically through service logic and locks, while PostgreSQL remains the final authority.

### 3.4 Idempotency is persisted

Booking and cancellation require an `Idempotency-Key`. The request hash includes the actor, target slot, patient, and appointment type where applicable.

For a matching existing record, the original serialized response is replayed. Reusing the same key with a different request hash is rejected. This protects clients from duplicate effects after network retries.

### 3.5 Provider-offline workflow is asynchronous

`POST /api/providers/{id}/offline` marks the provider offline and asynchronously evaluates affected confirmed appointments. Each affected appointment receives a suggestion when a safe deterministic candidate exists. A recommendation can be accepted or rejected by the review workflow.

Accepting a suggestion locks the suggestion, target slot, appointment, and old slot as needed, then atomically:

1. Books the recommended slot.
2. Releases the old slot.
3. Moves the appointment to the recommended provider and slot.
4. Marks the appointment `RESCHEDULED`.
5. Marks the suggestion `ACCEPTED`.

Rejecting a suggestion marks it `REJECTED` without changing the appointment.

### 3.6 Stale recommendations expire

Suggestions are rechecked before display and before acceptance. If the recommended slot is no longer open, the suggestion becomes `EXPIRED` and is not presented as actionable. Booking a slot also expires pending suggestions that point to it.

## 4. API and persistence contract

The demo API exposes:

```text
GET  /api/dashboard
POST /api/providers/{id}/offline
POST /api/slots/{id}/book
POST /api/slots/{id}/cancel
POST /api/suggestions/{id}/accept
POST /api/suggestions/{id}/reject
```

Booking and cancellation use:

```text
X-Provider-Id: PRV-002
Idempotency-Key: client-generated-unique-key
```

`X-Provider-Id` is a prototype actor identity. In production it must be derived from an authenticated JWT or session rather than trusted directly from a request header.

## 5. Alternatives considered

### 5.1 Let the LLM select the replacement appointment

**Rejected.** A probabilistic model must not control provider availability, appointment state, or booking decisions. It would be difficult to guarantee correctness, reproduce decisions, or prevent hallucinated slots.

### 5.2 Use only the LLM and omit deterministic fallback

**Rejected.** The workflow must remain operational during gateway outages, missing credentials, malformed responses, or rate limits. The rule-based explanation provides graceful degradation.

### 5.3 Use optimistic locking only

**Rejected for booking-critical reads.** Entity versions are present as a secondary guard, but pessimistic row locks make the read-check-write booking path explicit and prevent two transactions from both observing the same open slot.

### 5.4 Use an in-memory idempotency cache

**Rejected.** A cache is lost on restart and does not coordinate multiple application instances. Idempotency records are persisted in PostgreSQL and protected by a database uniqueness constraint.

### 5.5 Use a background queue for all rescheduling work

**Deferred.** The current `@Async` flow is sufficient for the assignment and demo. A production deployment with retries, durable delivery, dead-letter handling, and observability should use a durable queue or workflow engine.

## 6. Security and privacy decisions

- LLM credentials are environment variables and are not stored in source control.
- Logs record model/configuration state and slot identifiers but do not log API keys.
- The LLM receives only the context needed to explain an already-selected recommendation.
- The current provider header is a documented prototype simplification, not production authentication.
- Production deployment should add authenticated identity, authorization checks, request validation, rate limiting, and a privacy review for patient data sent to any external gateway.

## 7. Testing strategy

### Unit and service tests

The regular Maven test suite covers:

- LLM request headers, model, parsing, missing-key fallback, and malformed-response fallback.
- Controller mapping for provider ownership and unavailable-slot conflicts.
- Idempotency replay and request-hash mismatch.
- Stale recommendation expiry and visibility.
- Recommendation accept/reject transitions.
- Appointment and slot state transitions.

Run with:

```bash
cd backend
mvn test
```

### PostgreSQL integration tests

The opt-in `SchedulingPostgresIntegrationIT` covers:

- Two concurrent different requests competing for one slot.
- Two concurrent retries using the same idempotency key.
- Rollback behavior after a rejected booking.
- The partial unique index for active appointments.
- Reuse of a slot after a cancelled appointment.

Run with PostgreSQL available:

```bash
docker compose up -d postgres
cd backend
mvn -Ppostgres-integration -Duser.timezone=UTC clean test
```

The standard suite intentionally excludes `*IT.java` so local unit tests do not require a running database. The integration command is the required proof of PostgreSQL locking behavior before production deployment.

## 8. Demo script — five minutes

1. Start PostgreSQL:

   ```bash
   docker compose up -d postgres
   ```

2. Start the backend from `backend` with `mvn spring-boot:run` and the frontend from `frontend` with `npm install && npm run dev`.

3. Open `http://localhost:5173` and show the seeded providers, appointments, and open slots.

4. Mark `PRV-001` / Dr. Sarah Chen as offline using the UI or:

   ```bash
   curl -X POST http://localhost:8080/api/providers/PRV-001/offline
   ```

5. Show that the affected confirmed appointments produce pending suggestions. Explain that provider and slot selection came from deterministic Java rules.

6. Show `AI explanation` when the gateway is configured, or `Scheduling explanation` when the deterministic fallback is active.

7. Accept one recommendation and show the new appointment, booked target slot, released old slot, and accepted suggestion.

8. Optionally demonstrate a booking conflict and explain that row locks, idempotency, and the partial unique index protect the database.

## 9. Addendum mapping

### Addendum A — seed data

The repository contains equivalent Flyway seed data in:

```text
backend/src/main/resources/db/migration/V2__seed_demo_data.sql
```

It creates the five providers, six slots, and four confirmed appointments described by the assignment addendum. The migration uses PostgreSQL `CURRENT_TIMESTAMP` intervals so the demo schedule remains relative to database startup rather than becoming permanently stale.

### Addendum B — LLM gateway

The repository implements the OpenAI-compatible gateway contract in:

```text
backend/src/main/java/com/carebridge/rescheduling/service/LlmRecommendationClient.java
```

The client can target LiteLLM, Gemini/Groq-compatible gateways, or a local Ollama-compatible endpoint by changing `LLM_BASE_URL`, `LLM_MODEL`, and `LLM_PRODUCT`. The rest of the scheduling workflow is provider-neutral and remains deterministic.

## 10. Consequences

### Positive

- Operational decisions are reproducible and auditable.
- LLM failure does not block rescheduling.
- PostgreSQL protects critical invariants under concurrency.
- Recommendation source is persisted and visible to users.
- The demo and tests can be run without exposing credentials.

### Trade-offs and limitations

- The current prototype uses a trusted provider header instead of real authentication.
- `@Async` processing is not durable across process failure.
- The external LLM call adds latency and requires gateway observability in production.
- The standard test suite and PostgreSQL integration suite are separate commands.
- Production deployment should add structured error handling, Bean Validation, authentication, authorization, durable async processing, and CI execution of the integration profile.

## 11. Decision outcome

This design is accepted for the assignment and demo because it keeps the LLM safely bounded to explanation generation while making Java rules and PostgreSQL transactions authoritative for scheduling and booking. The documented limitations identify the work required before production use.