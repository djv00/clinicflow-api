CREATE TABLE patients (
    id UUID PRIMARY KEY,
    medical_record_number VARCHAR(50) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    date_of_birth DATE NOT NULL,
    CONSTRAINT uk_patients_medical_record_number UNIQUE (medical_record_number)
);

CREATE TABLE departments (
    id UUID PRIMARY KEY,
    department_code VARCHAR(30) NOT NULL,
    department_name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL,
    CONSTRAINT uk_departments_department_code UNIQUE (department_code)
);

CREATE TABLE wards (
    id UUID PRIMARY KEY,
    ward_code VARCHAR(30) NOT NULL,
    ward_name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL,
    CONSTRAINT uk_wards_ward_code UNIQUE (ward_code)
);

CREATE TABLE beds (
    id UUID PRIMARY KEY,
    bed_number VARCHAR(30) NOT NULL,
    ward_id UUID NOT NULL,
    active BOOLEAN NOT NULL,
    CONSTRAINT uk_beds_ward_bed_number UNIQUE (ward_id, bed_number),
    CONSTRAINT fk_beds_ward FOREIGN KEY (ward_id) REFERENCES wards (id)
);

CREATE TABLE encounters (
    id UUID PRIMARY KEY,
    encounter_number VARCHAR(50) NOT NULL,
    patient_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    admitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    discharged_at TIMESTAMP WITH TIME ZONE,
    admission_cancelled_at TIMESTAMP WITH TIME ZONE,
    admission_cancelled_by VARCHAR(100),
    CONSTRAINT uk_encounters_encounter_number UNIQUE (encounter_number),
    CONSTRAINT fk_encounters_patient FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT ck_encounters_status CHECK (
        status IN ('ADMITTED', 'IN_DEPARTMENT', 'DISCHARGED', 'ADMISSION_CANCELLED')
    )
);

CREATE TABLE encounter_locations (
    id UUID PRIMARY KEY,
    encounter_id UUID NOT NULL,
    department_id UUID NOT NULL,
    ward_id UUID NOT NULL,
    bed_id UUID,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_encounter_locations_encounter FOREIGN KEY (encounter_id) REFERENCES encounters (id),
    CONSTRAINT fk_encounter_locations_department FOREIGN KEY (department_id) REFERENCES departments (id),
    CONSTRAINT fk_encounter_locations_ward FOREIGN KEY (ward_id) REFERENCES wards (id),
    CONSTRAINT fk_encounter_locations_bed FOREIGN KEY (bed_id) REFERENCES beds (id)
);

CREATE INDEX idx_encounter_locations_encounter_end
    ON encounter_locations (encounter_id, ended_at);

CREATE TABLE encounter_discharges (
    id UUID PRIMARY KEY,
    encounter_id UUID NOT NULL,
    location_id UUID NOT NULL,
    discharged_at TIMESTAMP WITH TIME ZONE NOT NULL,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    cancelled_by VARCHAR(100),
    restored_location_id UUID,
    CONSTRAINT uk_discharges_location UNIQUE (location_id),
    CONSTRAINT fk_discharges_encounter FOREIGN KEY (encounter_id) REFERENCES encounters (id),
    CONSTRAINT fk_discharges_location FOREIGN KEY (location_id) REFERENCES encounter_locations (id),
    CONSTRAINT fk_discharges_restored_location FOREIGN KEY (restored_location_id) REFERENCES encounter_locations (id)
);

CREATE INDEX idx_discharges_encounter_cancelled
    ON encounter_discharges (encounter_id, cancelled_at);
