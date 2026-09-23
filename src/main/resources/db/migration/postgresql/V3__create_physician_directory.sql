CREATE TABLE physicians (
    id UUID PRIMARY KEY,
    physician_code VARCHAR(30) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uk_physicians_physician_code UNIQUE (physician_code)
);

CREATE TABLE physician_departments (
    physician_id UUID NOT NULL,
    department_id UUID NOT NULL,
    CONSTRAINT pk_physician_departments PRIMARY KEY (physician_id, department_id),
    CONSTRAINT fk_physician_departments_physician FOREIGN KEY (physician_id) REFERENCES physicians (id),
    CONSTRAINT fk_physician_departments_department FOREIGN KEY (department_id) REFERENCES departments (id)
);

CREATE INDEX idx_physician_departments_department ON physician_departments (department_id);
