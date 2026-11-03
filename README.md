# CareBridge AI Re-Scheduling Engine

## Run
Start PostgreSQL first from the repository root with `docker compose up -d postgres`.
Backend: `cd C:\Users\zycus\Documents\carebridge-rescheduling\backend` then `mvn spring-boot:run`.
Frontend: `cd C:\Users\zycus\Documents\carebridge-rescheduling\frontend`, `npm install`, then `npm run dev`.

The dashboard is at `http://localhost:5173`. PostgreSQL stores providers, slots, appointments, suggestions, and idempotency records. Demo data is inserted once by the Flyway migration. Taking a provider offline asynchronously creates suggestions. Accepting a suggestion atomically books the recommended slot, releases the old slot, and updates the appointment.

## AI gateway
Set `LLM_API_KEY` in the backend process. Optional overrides are `LLM_BASE_URL` and `LLM_MODEL`. The key is intentionally not stored in this repository. If the gateway is unset or unavailable, the deterministic nearest-open-slot explanation is used.

## API
`GET /api/dashboard`, `POST /api/providers/{id}/offline`, `POST /api/slots/{id}/book`, `POST /api/slots/{id}/cancel`, `POST /api/suggestions/{id}/accept`, and `POST /api/suggestions/{id}/reject`.

Booking and cancellation require these headers:

```text
X-Provider-Id: PRV-002
Idempotency-Key: a-client-generated-unique-key
```

Booking body example:

```json
{
  "patientName": "Test Patient",
  "appointmentType": "ROUTINE"
}
```

The same provider can retry a request with the same idempotency key and receives the original response. Reusing an idempotency key with different request data is rejected. Slot booking and cancellation use PostgreSQL row locks and transactions, so concurrent requests cannot both claim the same slot.