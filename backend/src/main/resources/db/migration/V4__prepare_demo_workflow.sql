-- Prepare the demo with exactly two booked times, four available times,
-- and two unavailable doctors with pending rescheduling recommendations.

DELETE FROM suggestions;
DELETE FROM idempotency_records;

UPDATE appointments
SET status = 'CANCELLED'
WHERE id IN ('APT-002', 'APT-004');

UPDATE appointments
SET provider_id = 'PRV-001', slot_id = 'SLT-001', status = 'CONFIRMED'
WHERE id = 'APT-001';

UPDATE appointments
SET provider_id = 'PRV-003', slot_id = 'SLT-003', status = 'CONFIRMED'
WHERE id = 'APT-003';

UPDATE slots
SET status = CASE
    WHEN id IN ('SLT-001', 'SLT-003') THEN 'BOOKED'
    WHEN id IN ('SLT-002', 'SLT-004', 'SLT-005', 'SLT-006') THEN 'OPEN'
    ELSE status
END,
cancelled_by_provider_id = NULL,
cancelled_at = NULL;

UPDATE providers
SET status = CASE
    WHEN id IN ('PRV-001', 'PRV-003') THEN 'OFFLINE'
    ELSE 'AVAILABLE'
END;

INSERT INTO suggestions (
    id, appointment_id, patient_name, current_provider, current_slot,
    recommended_provider, recommended_slot, recommended_start, confidence,
    explanation, status, trigger_type, created_at
)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'APT-001', 'John Martinez',
     'Dr. Sarah Chen', 'SLT-001', 'Dr. James Okonkwo', 'SLT-004',
     (SELECT start_time FROM slots WHERE id = 'SLT-004'), 0.8600,
     'Nearest available appointment time with another doctor; avoids double-booking.',
     'PENDING', 'PROVIDER_OFFLINE', CURRENT_TIMESTAMP),
    ('22222222-2222-2222-2222-222222222222', 'APT-003', 'Aisha Patel',
     'Dr. Priya Sharma', 'SLT-003', 'Dr. Rahul Verma', 'SLT-005',
     (SELECT start_time FROM slots WHERE id = 'SLT-005'), 0.8600,
     'Nearest available appointment time with another doctor; avoids double-booking.',
     'PENDING', 'PROVIDER_OFFLINE', CURRENT_TIMESTAMP)
ON CONFLICT (id) DO UPDATE SET
    status = EXCLUDED.status,
    recommended_slot = EXCLUDED.recommended_slot,
    recommended_start = EXCLUDED.recommended_start;