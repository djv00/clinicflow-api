package com.jiangyudai.clinicflow.common.time;

import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** Keeps request validation and business workflows on the same configurable time source. */
@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {
    @Bean
    Clock businessClock(@Value("${clinicflow.time-zone:UTC}") String timeZone) {
        return Clock.system(ZoneId.of(timeZone));
    }

    @Bean
    ValidationConfigurationCustomizer validationClock(Clock clock) {
        return configuration -> configuration.clockProvider(() -> clock);
    }
}
