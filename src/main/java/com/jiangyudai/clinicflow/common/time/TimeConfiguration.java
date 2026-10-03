package com.jiangyudai.clinicflow.common.time;

import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Keeps request validation and business workflows on the same configurable time source. */
@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {
    @Bean
    Clock businessClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    ValidationConfigurationCustomizer validationClock(Clock clock) {
        return configuration -> configuration.clockProvider(() -> clock);
    }
}
