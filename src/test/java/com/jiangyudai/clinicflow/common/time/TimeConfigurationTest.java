package com.jiangyudai.clinicflow.common.time;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class TimeConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(TimeConfiguration.class);

    @Test
    void defaultsToUtcRegardlessOfTheHostTimeZone() {
        context.run(application -> assertThat(application.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("UTC")));
    }

    @Test
    void usesTheConfiguredHospitalTimeZone() {
        context.withPropertyValues("clinicflow.time-zone=America/Toronto")
                .run(application -> assertThat(application.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("America/Toronto")));
    }
}
