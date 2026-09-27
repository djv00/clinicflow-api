DO $$
DECLARE
    trim_characters TEXT;
BEGIN
    -- Match Java String.trim(); PostgreSQL text cannot contain the NUL character.
    SELECT string_agg(chr(code), '' ORDER BY code)
    INTO trim_characters FROM generate_series(1, 32) AS characters(code);

    IF EXISTS (SELECT 1 FROM patients WHERE btrim(medical_record_number, trim_characters) = '')
        OR EXISTS (
            SELECT 1 FROM patients
            GROUP BY btrim(medical_record_number, trim_characters) HAVING count(*) > 1
        ) THEN
        RAISE EXCEPTION 'Patient numbers are blank or collide after trimming. Resolve the existing records before migrating.';
    END IF;

    IF EXISTS (SELECT 1 FROM encounters WHERE btrim(encounter_number, trim_characters) = '')
        OR EXISTS (
            SELECT 1 FROM encounters
            GROUP BY btrim(encounter_number, trim_characters) HAVING count(*) > 1
        ) THEN
        RAISE EXCEPTION 'Encounter numbers are blank or collide after trimming. Resolve the existing records before migrating.';
    END IF;

    UPDATE patients SET medical_record_number = btrim(medical_record_number, trim_characters)
    WHERE medical_record_number <> btrim(medical_record_number, trim_characters);

    UPDATE encounters SET encounter_number = btrim(encounter_number, trim_characters)
    WHERE encounter_number <> btrim(encounter_number, trim_characters);
END;
$$;
