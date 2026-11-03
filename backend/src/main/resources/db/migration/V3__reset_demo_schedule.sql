-- Restore a predictable demo state after interactive testing.
-- Four times are booked and two times are available for the booking demo.

DELETE FROM suggestions;
DELETE FROM idempotency_records;

UPDATE appointments
SET status = 'CANCELLED'
WHERE slot_id IN ('SLT-004', 'SLT-005');

UPDATE slots
SET status = CASE
    WHEN id IN ('SLT-001', 'SLT-002', 'SLT-003', 'SLT-006') THEN 'BOOKED'
    WHEN id IN ('SLT-004', 'SLT-005') THEN 'OPEN'
    ELSE status
END,
cancelled_by_provider_id = NULL,
cancelled_at = NULL;

UPDATE providers
SET status = CASE
    WHEN id IN ('PRV-002', 'PRV-004') THEN 'AVAILABLE'
    ELSE 'BUSY'
END;