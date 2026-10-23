package com.iaf.console.kit.arch;

import java.util.List;
import java.util.regex.Pattern;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * The architecture rules every console service checks in its tests: tenant reads carry an
 * {@link AccountScope}, the domain stays pure, the application sees no adapter, and a slice's
 * adapters never reach into another slice's adapters. A service lists the rules as
 * {@code @ArchTest} fields, or calls {@link #checkAll(String)} on its root package.
 */
public final class ConsoleArchitectureRules {

    private static final Pattern READ = Pattern.compile("^(find|get|list|search|count|exists)");
    private static final String MONO = "reactor.core.publisher.Mono";
    private static final String FLUX = "reactor.core.publisher.Flux";

    /**
     * Every reactive read of an outbound port takes an {@link AccountScope} first, unless the method
     * or the port says {@link GlobalRead}.
     */
    public static final ArchRule SCOPED_READS = ArchRuleDefinition.classes()
            .that().resideInAPackage("..ports.outbound..")
            .and().areInterfaces()
            .should(haveScopedReads())
            .allowEmptyShould(true)
            .as("outbound ports take an AccountScope as the first parameter of every reactive read, unless @GlobalRead");

    /** The domain depends on nothing from the adapters, the application or Spring. */
    public static final ArchRule PURE_DOMAIN = ArchRuleDefinition.noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..adapters..", "..application..", "org.springframework..")
            .allowEmptyShould(true)
            .as("the domain depends on nothing from adapters, application or Spring");

    /** The application depends on no adapter: it sees ports only. */
    public static final ArchRule APPLICATION_WITHOUT_ADAPTERS = ArchRuleDefinition.noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapters..")
            .allowEmptyShould(true)
            .as("the application depends on no adapter");

    /** An adapter depends on no adapter of another slice. */
    public static final ArchRule ADAPTERS_STAY_IN_THEIR_SLICE = ArchRuleDefinition.classes()
            .that().resideInAPackage("..adapters..")
            .should(onlyDependOnAdaptersOfTheirSlice())
            .allowEmptyShould(true)
            .as("adapters depend on no adapter of another slice");

    /** All of the above, in the order they are declared. */
    public static final List<ArchRule> ALL = List.of(SCOPED_READS, PURE_DOMAIN, APPLICATION_WITHOUT_ADAPTERS, ADAPTERS_STAY_IN_THEIR_SLICE);

    private ConsoleArchitectureRules() {
    }

    /** Imports the production classes of a package (tests and jars left out) and checks every rule. */
    public static void checkAll(String rootPackage) {
        checkAll(classesOf(rootPackage));
    }

    public static void checkAll(JavaClasses classes) {
        for (ArchRule rule : ALL) {
            rule.check(classes);
        }
    }

    /** The production classes under a package, without tests and jars. */
    public static JavaClasses classesOf(String rootPackage) {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages(rootPackage);
    }

    /** True when a method is a reactive read by the rule's definition: returns Mono or Flux, named like a query. */
    public static boolean isReactiveRead(JavaMethod method) {
        String returned = method.getRawReturnType().getName();
        return (MONO.equals(returned) || FLUX.equals(returned)) && READ.matcher(method.getName()).find();
    }

    private static ArchCondition<JavaClass> haveScopedReads() {
        return new ArchCondition<>("take an AccountScope as the first parameter of every reactive read") {
            @Override
            public void check(JavaClass port, ConditionEvents events) {
                if (port.isAnnotatedWith(GlobalRead.class)) {
                    return;
                }
                for (JavaMethod method : port.getMethods()) {
                    if (!isReactiveRead(method) || method.isAnnotatedWith(GlobalRead.class)) {
                        continue;
                    }
                    List<JavaClass> params = method.getRawParameterTypes();
                    boolean scoped = !params.isEmpty() && params.get(0).isEquivalentTo(AccountScope.class);
                    if (!scoped) {
                        events.add(SimpleConditionEvent.violated(method, String.format(
                                "method %s.%s reads without an AccountScope as its first parameter (annotate it @GlobalRead if it reads no tenant data)",
                                port.getName(), method.getName())));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> onlyDependOnAdaptersOfTheirSlice() {
        return new ArchCondition<>("depend on no adapter of another slice") {
            @Override
            public void check(JavaClass adapter, ConditionEvents events) {
                String slice = sliceOf(adapter.getPackageName());
                if (slice == null) {
                    return;
                }
                for (Dependency dependency : adapter.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String targetSlice = sliceOf(target.getPackageName());
                    if (targetSlice != null && !targetSlice.equals(slice)) {
                        events.add(SimpleConditionEvent.violated(dependency, String.format(
                                "%s (slice %s) depends on %s, an adapter of slice %s", adapter.getName(), slice, target.getName(), targetSlice)));
                    }
                }
            }
        };
    }

    /** The slice an adapter package belongs to: the package up to its {@code adapters} segment; null when it is no adapter. */
    static String sliceOf(String packageName) {
        String[] segments = packageName.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            if ("adapters".equals(segments[i])) {
                return String.join(".", List.of(segments).subList(0, i));
            }
        }
        return null;
    }
}
