package com.jiangyudai.clinicflow.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Guards the boundaries described in docs/architecture.md without imposing strict module isolation. */
class ArchitectureTest {
    private static final JavaClasses APPLICATION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.jiangyudai.clinicflow");

    @Test
    void controllersDoNotAccessPersistence() {
        noClasses().that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().resideInAnyPackage("..repository..", "jakarta.persistence..")
                .check(APPLICATION);
        noClasses().that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
                .because("services assemble response DTOs within their transaction")
                .check(APPLICATION);
    }

    @Test
    void servicesDoNotReadHttpOrTheSecurityContext() {
        noClasses().that().resideInAPackage("..service..")
                .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..web..",
                        "jakarta.servlet..", "org.springframework.http..", "org.springframework.security.core.context..")
                .because("controllers pass the authenticated operator explicitly")
                .check(APPLICATION);
    }

    @Test
    void entitiesDoNotDependOnApplicationOrTransportLayers() {
        noClasses().that().areAnnotatedWith(Entity.class)
                .should().dependOnClassesThat().resideInAnyPackage("..service..", "..repository..", "..controller..",
                        "..dto..", "..web..", "jakarta.servlet..", "org.springframework.http..")
                .check(APPLICATION);
    }

    @Test
    void commonUtilitiesDoNotKnowBusinessModules() {
        noClasses().that().resideInAPackage("..common..")
                .should().dependOnClassesThat().resideInAnyPackage("..patient..", "..encounter..", "..location..",
                        "..physician..", "..security..", "..web..")
                .check(APPLICATION);
    }

    @Test
    void businessTimeIsExplicit() {
        noClasses().that().resideOutsideOfPackage("..common.time..")
                .should().callMethodWhere(describe("read system time without the injected Clock", call -> {
                    var target = call.getTarget();
                    boolean implicitNow = target.getOwner().getPackageName().equals("java.time")
                            && target.getName().equals("now")
                            && target.getRawParameterTypes().stream().noneMatch(type -> type.isEquivalentTo(Clock.class));
                    boolean systemClock = target.getOwner().isEquivalentTo(Clock.class)
                            && Set.of("system", "systemUTC", "systemDefaultZone", "tickMillis", "tickSeconds", "tickMinutes")
                            .contains(target.getName());
                    return implicitNow || systemClock;
                }))
                .because("workflow time must come from the injected Clock")
                .check(APPLICATION);
    }

    @Test
    void dtoFieldsDoNotExposeManagedEntities() {
        fields().that().areDeclaredInClassesThat().resideInAPackage("..dto..")
                .should(new ArchCondition<JavaField>("contain transport values rather than JPA entities") {
                    @Override
                    public void check(JavaField field, ConditionEvents events) {
                        for (var type : field.getType().getAllInvolvedRawTypes()) {
                            if (type.isAnnotatedWith(Entity.class)) {
                                events.add(SimpleConditionEvent.violated(field,
                                        field.getFullName() + " exposes entity " + type.getName()));
                            }
                        }
                    }
                }).check(APPLICATION);
    }
}
