CREATE TABLE providers (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    specialty VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE slots (
    id VARCHAR(50) PRIMARY KEY,
    provider_id VARCHAR(50) NOT NULL REFERENCES providers(id),
    start_time TIMESTAMPTZ NOT NULL,
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes > 0),
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    cancelled_by_provider_id VARCHAR(50),
    cancelled_at TIMESTAMPTZ
);

CREATE TABLE appointments (
    id VARCHAR(50) PRIMARY KEY,
    patient_name VARCHAR(200) NOT NULL,
    appointment_type VARCHAR(100) NOT NULL,
    provider_id VARCHAR(50) NOT NULL REFERENCES providers(id),
    slot_id VARCHAR(50) NOT NULL REFERENCES slots(id),
    status VARCHAR(40) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE suggestions (
    id UUID PRIMARY KEY,
    appointment_id VARCHAR(50) NOT NULL REFERENCES appointments(id),
    patient_name VARCHAR(200) NOT NULL,
    current_provider VARCHAR(200) NOT NULL,
    current_slot VARCHAR(50) NOT NULL,
    recommended_provider VARCHAR(200) NOT NULL,
    recommended_slot VARCHAR(50),
    recommended_start TIMESTAMPTZ,
    confidence NUMERIC(5, 4) NOT NULL,
    explanation TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    trigger_type VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE idempotency_records (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(200) NOT NULL,
    actor_id VARCHAR(50) NOT NULL,
    operation VARCHAR(40) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_status INTEGER NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (idempotency_key, actor_id, operation)
);

CREATE UNIQUE INDEX one_active_appointment_per_slot
    ON appointments(slot_id)
    WHERE status IN ('CONFIRMED', 'RESCHEDULE_PENDING', 'RESCHEDULED');