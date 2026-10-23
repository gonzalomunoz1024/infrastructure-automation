package com.iaf.console.kit.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

class ConsoleArchitectureRulesTest {

    private static final JavaClasses GOOD = new ClassFileImporter().importPackages("com.iaf.console.kit.archsample.good");
    private static final JavaClasses BAD = new ClassFileImporter().importPackages("com.iaf.console.kit.archsample.bad");

    @Test
    void aWellShapedSlicePasses() {
        assertThatCode(() -> ConsoleArchitectureRules.checkAll(GOOD)).doesNotThrowAnyException();
    }

    @Test
    void readsWithoutAScopeAreNamed() {
        assertThatThrownBy(() -> ConsoleArchitectureRules.SCOPED_READS.check(BAD))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("UnscopedReads.findThing")
                .hasMessageContaining("UnscopedReads.listThings")
                .hasMessageContaining("UnscopedReads.existsThing")
                .hasMessageContaining("@GlobalRead");
    }

    @Test
    void aDomainThatLeansOnSpringFails() {
        assertThatThrownBy(() -> ConsoleArchitectureRules.PURE_DOMAIN.check(BAD))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("LeakyThing")
                .hasMessageContaining("org.springframework");
    }

    @Test
    void anApplicationThatBuildsAdaptersFails() {
        assertThatThrownBy(() -> ConsoleArchitectureRules.APPLICATION_WITHOUT_ADAPTERS.check(BAD))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("LeakyService")
                .hasMessageContaining("MemoryReads");
    }

    @Test
    void anAdapterThatReachesIntoAnotherSliceFails() {
        assertThatThrownBy(() -> ConsoleArchitectureRules.ADAPTERS_STAY_IN_THEIR_SLICE.check(BAD))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("CrossAdapter")
                .hasMessageContaining("slice com.iaf.console.kit.archsample.bad.registry");
        assertThat(ConsoleArchitectureRules.sliceOf("com.iaf.console.x.runs.adapters.inbound")).isEqualTo("com.iaf.console.x.runs");
        assertThat(ConsoleArchitectureRules.sliceOf("com.iaf.console.x.runs.domain")).isNull();
    }

    @Test
    void theRulesPassOnAnEmptyPackage() {
        assertThatCode(() -> ConsoleArchitectureRules.checkAll("com.iaf.console.kit.archsample.none")).doesNotThrowAnyException();
    }

    @Test
    void theKitItselfPasses() {
        assertThatCode(() -> ConsoleArchitectureRules.checkAll(new ClassFileImporter().importPackages("com.iaf.console.kit.outbox",
                "com.iaf.console.kit.idempotency", "com.iaf.console.kit.events"))).doesNotThrowAnyException();
    }
}
