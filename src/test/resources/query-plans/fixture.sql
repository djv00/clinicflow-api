-- Synthetic, deterministic data for a generated test schema only.
INSERT INTO departments
SELECT md5('department-' || i)::uuid, 'QUERY-D-' || i, 'Test Department ' || i, true
FROM generate_series(1, 20) AS i;

INSERT INTO wards
SELECT md5('ward-' || i)::uuid, 'QUERY-W-' || i, 'Test Ward ' || i, true
FROM generate_series(1, 10) AS i;

INSERT INTO beds
SELECT md5('bed-' || i)::uuid, 'QUERY-B-' || i, md5('ward-' || (1 + (i - 1) % 10))::uuid, true
FROM generate_series(1, 1000) AS i;

INSERT INTO patients
SELECT md5('patient-' || i)::uuid, 'QUERY-P-' || lpad(i::text, 6, '0'), 'Test', 'Patient ' || i, DATE '1970-01-01'
FROM generate_series(1, 5000) AS i;

-- Thirty past stays per patient. Globally separated intervals also avoid overlapping historical bed use.
INSERT INTO encounters (id, encounter_number, patient_id, status, admitted_at, discharged_at)
SELECT md5('history-' || i)::uuid, 'QUERY-H-' || lpad(i::text, 6, '0'),
       md5('patient-' || (1 + (i - 1) % 5000))::uuid, 'DISCHARGED',
       TIMESTAMPTZ '2000-01-01 00:00:00+00' + i * INTERVAL '1 hour',
       TIMESTAMPTZ '2000-01-01 00:55:00+00' + i * INTERVAL '1 hour'
FROM generate_series(1, 150000) AS i;

INSERT INTO encounter_locations
SELECT md5('history-location-' || i)::uuid, md5('history-' || i)::uuid,
       md5('department-' || (1 + (i - 1) % 20))::uuid, md5('ward-' || (1 + (i - 1) % 10))::uuid,
       md5('bed-' || (1 + (i - 1) % 1000))::uuid,
       TIMESTAMPTZ '2000-01-01 00:05:00+00' + i * INTERVAL '1 hour',
       TIMESTAMPTZ '2000-01-01 00:55:00+00' + i * INTERVAL '1 hour'
FROM generate_series(1, 150000) AS i;

INSERT INTO encounter_discharges (id, encounter_id, location_id, discharged_at)
SELECT md5('discharge-' || i)::uuid, md5('history-' || i)::uuid, md5('history-location-' || i)::uuid,
       TIMESTAMPTZ '2000-01-01 00:55:00+00' + i * INTERVAL '1 hour'
FROM generate_series(1, 150000) AS i;

INSERT INTO encounters (id, encounter_number, patient_id, status, admitted_at)
SELECT md5('active-' || i)::uuid, 'QUERY-A-' || lpad(i::text, 6, '0'), md5('patient-' || i)::uuid,
       CASE WHEN i % 5 = 0 THEN 'ADMITTED' ELSE 'IN_DEPARTMENT' END,
       TIMESTAMPTZ '2025-09-01 09:00:00+00' + (i % 100) * INTERVAL '1 minute'
FROM generate_series(1, 1000) AS i;

INSERT INTO encounter_locations
SELECT md5('active-location-' || i)::uuid, md5('active-' || i)::uuid,
       md5('department-' || (1 + ((i - 1) / 10) % 20))::uuid, md5('ward-' || (1 + (i - 1) % 10))::uuid,
       CASE WHEN i % 7 = 0 THEN NULL ELSE md5('bed-' || i)::uuid END,
       TIMESTAMPTZ '2025-09-01 11:00:00+00', NULL
FROM generate_series(1, 1000) AS i WHERE i % 5 <> 0;

INSERT INTO physicians
SELECT md5('physician-' || i)::uuid, 'QUERY-DR-' || lpad(i::text, 6, '0'), 'Test',
       'Doctor ' || lpad(i::text, 6, '0'), i % 5 <> 0, 0
FROM generate_series(1, 1000) AS i;

INSERT INTO physician_departments
SELECT md5('physician-' || i)::uuid, md5('department-' || (1 + (i - 1) % 20))::uuid
FROM generate_series(1, 1000) AS i
UNION ALL
SELECT md5('physician-' || i)::uuid, md5('department-' || (1 + i % 20))::uuid
FROM generate_series(1, 1000) AS i;

ANALYZE patients;
ANALYZE encounters;
ANALYZE encounter_locations;
ANALYZE encounter_discharges;
ANALYZE physicians;
ANALYZE physician_departments;
ANALYZE departments;
ANALYZE wards;
ANALYZE beds;
