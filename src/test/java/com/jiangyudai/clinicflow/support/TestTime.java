package com.jiangyudai.clinicflow.support;

import java.time.OffsetDateTime;

/** Reference instant for entity and service unit tests; independent of the machine clock. */
public final class TestTime {
    public static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-03T12:00:00Z");

    private TestTime() {
    }
}
