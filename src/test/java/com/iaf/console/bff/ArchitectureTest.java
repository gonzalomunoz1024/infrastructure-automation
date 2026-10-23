package com.iaf.console.bff;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.arch.ConsoleArchitectureRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/** The kit's layout rules over the whole code base, plus this service's own: only demo adapters touch platform-fake. */
class ArchitectureTest {

    private static final String ROOT = "com.iaf.console.bff";

    @Test
    void theKitsRulesHold() {
        ConsoleArchitectureRules.checkAll(ROOT);
    }

    @Test
    void onlyDemoCodeDependsOnThePlatformFake() {
        JavaClasses classes = ConsoleArchitectureRules.classesOf(ROOT);
        ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("..demo..")
                .should().dependOnClassesThat().resideInAPackage("com.iaf.console.fake..")
                .as("only the demo adapters and the demo slice depend on platform-fake")
                .check(classes);
    }

    @Test
    void theDomainKnowsNoWebClientOrJackson() {
        JavaClasses classes = ConsoleArchitectureRules.classesOf(ROOT);
        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "reactor.netty..", "com.mongodb..")
                .as("the domain depends on no web, Mongo or Spring class")
                .check(classes);
    }
}
