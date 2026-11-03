INSERT INTO providers (id, name, specialty, status)
VALUES
    ('PRV-001', 'Dr. Sarah Chen', 'GENERAL', 'BUSY'),
    ('PRV-002', 'Dr. James Okonkwo', 'SPECIALIST', 'AVAILABLE'),
    ('PRV-003', 'Dr. Priya Sharma', 'PEDIATRIC', 'BUSY'),
    ('PRV-004', 'Dr. Rahul Verma', 'GENERAL', 'AVAILABLE'),
    ('PRV-005', 'Dr. Ananya Iyer', 'SPECIALIST', 'BUSY')
ON CONFLICT (id) DO NOTHING;

INSERT INTO slots (id, provider_id, start_time, duration_minutes, status)
VALUES
    ('SLT-001', 'PRV-001', CURRENT_TIMESTAMP + INTERVAL '1 hour', 30, 'BOOKED'),
    ('SLT-002', 'PRV-001', CURRENT_TIMESTAMP + INTERVAL '2 hours', 30, 'BOOKED'),
    ('SLT-003', 'PRV-003', CURRENT_TIMESTAMP + INTERVAL '90 minutes', 45, 'BOOKED'),
    ('SLT-004', 'PRV-002', CURRENT_TIMESTAMP + INTERVAL '3 hours', 30, 'OPEN'),
    ('SLT-005', 'PRV-004', CURRENT_TIMESTAMP + INTERVAL '150 minutes', 30, 'OPEN'),
    ('SLT-006', 'PRV-005', CURRENT_TIMESTAMP + INTERVAL '4 hours', 60, 'BOOKED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO appointments (id, patient_name, appointment_type, provider_id, slot_id, status)
VALUES
    ('APT-001', 'John Martinez', 'ROUTINE', 'PRV-001', 'SLT-001', 'CONFIRMED'),
    ('APT-002', 'Emily Watson', 'FOLLOW_UP', 'PRV-001', 'SLT-002', 'CONFIRMED'),
    ('APT-003', 'Aisha Patel', 'URGENT', 'PRV-003', 'SLT-003', 'CONFIRMED'),
    ('APT-004', 'Robert Kim', 'ROUTINE', 'PRV-005', 'SLT-006', 'CONFIRMED')
ON CONFLICT (id) DO NOTHING;