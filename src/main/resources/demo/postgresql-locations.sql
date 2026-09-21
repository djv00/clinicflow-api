INSERT INTO departments (id, department_code, department_name, active) VALUES
('10000000-0000-0000-0000-000000000001', 'DEMO-MED', 'Demo General Medicine', true),
('10000000-0000-0000-0000-000000000002', 'DEMO-REHAB', 'Demo Rehabilitation', true)
ON CONFLICT (department_code) DO NOTHING;

INSERT INTO wards (id, ward_code, ward_name, active) VALUES
('20000000-0000-0000-0000-000000000001', 'DEMO-WARD-1', 'Demo Ward One', true),
('20000000-0000-0000-0000-000000000002', 'DEMO-WARD-2', 'Demo Ward Two', true)
ON CONFLICT (ward_code) DO NOTHING;

-- Resolve the ward by code: an existing dictionary may use a different UUID.
INSERT INTO beds (id, bed_number, ward_id, active)
SELECT fixture.id::uuid, fixture.bed_number, ward.id, fixture.active
FROM (VALUES
    ('30000000-0000-0000-0000-000000000001', 'DEMO-WARD-1', '01', true),
    ('30000000-0000-0000-0000-000000000002', 'DEMO-WARD-2', '01', true),
    ('30000000-0000-0000-0000-000000000003', 'DEMO-WARD-1', '02', false)
) AS fixture(id, ward_code, bed_number, active)
JOIN wards ward ON ward.ward_code = fixture.ward_code
ON CONFLICT (ward_id, bed_number) DO NOTHING;
