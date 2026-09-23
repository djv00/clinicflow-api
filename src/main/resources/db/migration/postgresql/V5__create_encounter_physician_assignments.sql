CREATE TABLE encounter_physician_assignments (
    id UUID PRIMARY KEY,
    encounter_id UUID NOT NULL,
    physician_id UUID NOT NULL,
    department_id UUID NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    assigned_by VARCHAR(100) NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    end_reason VARCHAR(30),
    ended_by VARCHAR(100),
    version BIGINT NOT NULL,
    CONSTRAINT fk_physician_assignments_encounter FOREIGN KEY (encounter_id) REFERENCES encounters (id),
    CONSTRAINT fk_physician_assignments_physician FOREIGN KEY (physician_id) REFERENCES physicians (id),
    CONSTRAINT fk_physician_assignments_department FOREIGN KEY (department_id) REFERENCES departments (id),
    CONSTRAINT ck_physician_assignments_time CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT ck_physician_assignments_assigned_by CHECK (length(trim(assigned_by)) > 0),
    CONSTRAINT ck_physician_assignments_closure CHECK (
        (ended_at IS NULL AND end_reason IS NULL AND ended_by IS NULL)
        OR (ended_at IS NOT NULL AND end_reason IS NOT NULL AND ended_by IS NOT NULL
            AND end_reason IN ('REASSIGNED', 'DEPARTMENT_TRANSFER', 'DISCHARGE', 'RELEASED')
            AND length(trim(ended_by)) > 0)
    )
);

CREATE UNIQUE INDEX uk_physician_assignments_open_encounter
    ON encounter_physician_assignments (encounter_id) WHERE ended_at IS NULL;

CREATE INDEX idx_physician_assignments_encounter_start
    ON encounter_physician_assignments (encounter_id, started_at, id);
CREATE INDEX idx_physician_assignments_physician ON encounter_physician_assignments (physician_id);
CREATE INDEX idx_physician_assignments_department ON encounter_physician_assignments (department_id);
