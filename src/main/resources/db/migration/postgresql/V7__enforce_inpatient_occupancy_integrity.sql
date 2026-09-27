DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM encounters WHERE status IN ('ADMITTED', 'IN_DEPARTMENT')
        GROUP BY patient_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'A patient has multiple active encounters. Review the existing records before migrating.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM encounter_locations WHERE ended_at IS NULL
        GROUP BY encounter_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'An encounter has multiple open locations. Review the existing records before migrating.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM encounter_locations WHERE ended_at IS NULL AND bed_id IS NOT NULL
        GROUP BY bed_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'A bed has multiple open occupancies. Review the existing records before migrating.';
    END IF;

    IF EXISTS (SELECT 1 FROM encounter_locations WHERE ended_at < started_at) THEN
        RAISE EXCEPTION 'A location ends before it starts. Review the existing records before migrating.';
    END IF;
END;
$$;

CREATE UNIQUE INDEX uk_encounters_active_patient
    ON encounters (patient_id) WHERE status IN ('ADMITTED', 'IN_DEPARTMENT');

CREATE UNIQUE INDEX uk_encounter_locations_open_encounter
    ON encounter_locations (encounter_id) WHERE ended_at IS NULL;

CREATE UNIQUE INDEX uk_encounter_locations_open_bed
    ON encounter_locations (bed_id) WHERE ended_at IS NULL AND bed_id IS NOT NULL;

ALTER TABLE encounter_locations ADD CONSTRAINT ck_encounter_locations_time
    CHECK (ended_at IS NULL OR ended_at >= started_at);
