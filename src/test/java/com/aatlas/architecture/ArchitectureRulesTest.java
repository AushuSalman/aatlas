package com.aatlas.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Rules that catch the specific mistakes this codebase is prone to.
 *
 * <p>Each one exists because of a failure mode named in the blueprint, not because it is
 * a generic best practice. A rule nobody can justify is a rule that gets suppressed.
 */
@AnalyzeClasses(packages = "com.aatlas", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    /**
     * The engines must be deterministic, because the golden-file tests compare them to
     * the TypeScript prototype to the cent. A hidden call to {@code Instant.now()} makes
     * a test that passes in the morning fail at night, and makes a demo tenant's frozen
     * clock a lie. Time comes from {@code AatlasClock} or not at all.
     */
    @ArchTest
    static final ArchRule timeComesFromTheClock = noClasses()
            .that().resideInAPackage("com.aatlas.(engine|sell|buy|insights|analytics|history|prices)..")
            .should().callMethod(java.time.Instant.class, "now")
            .orShould().callMethod(java.time.LocalDate.class, "now")
            .orShould().callMethod(java.time.LocalDateTime.class, "now")
            .orShould().callMethod(System.class, "currentTimeMillis")
            .because("engine output must be reproducible; inject AatlasClock instead");

    /**
     * Money is numeric(14,4) end to end. A double cannot represent 0.1 exactly, so a
     * margin computed in floating point drifts from the prototype and the golden-file
     * tests stop meaning anything.
     */
    @ArchTest
    static final ArchRule noFloatingPointMoney = noClasses()
            .that().resideInAPackage("com.aatlas.(engine|sell|buy|history|prices)..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.Double")
            .because("money is BigDecimal; a double silently loses cents. Percentages and "
                    + "scores outside the pricing path belong in other packages");

    /**
     * A hash of a code is never a displayed figure. {@code Seeded} survives only to build
     * the sample catalogue deterministically and to simulate demo RFQ replies; every engine
     * reads the tenant's own rows through {@code history} or labels a reference estimate.
     *
     * <p>Ignored until every engine module has been rewired (the real-data waves); switched
     * on in the verification commit that closes them.
     */
    @com.tngtech.archunit.junit.ArchIgnore(reason = "enabled once every real-data engine worktree has merged")
    @ArchTest
    static final ArchRule seededIsForSampleGenerationOnly = noClasses()
            .that().resideOutsideOfPackages(
                    "com.aatlas.catalog.internal.seed..", "com.aatlas.rfq.internal..", "com.aatlas.common.seed..")
            .should().dependOnClassesThat().haveFullyQualifiedName("com.aatlas.common.seed.Seeded")
            .because("a displayed figure comes from the tenant's rows or is labelled as a reference estimate; "
                    + "Seeded only seeds the sample catalogue and demo RFQ replies");

    /** Constructor injection only: it makes a missing dependency a compile error. */
    @ArchTest
    static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    /** One logging API, so the JSON encoder actually sees every line. */
    @ArchTest
    static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    /**
     * Controllers stay thin: they read a snapshot and shape a response. The moment a
     * controller starts calling repositories directly, the "never compute item x store on
     * the request thread" rule has nowhere to live.
     */
    @ArchTest
    static final ArchRule controllersDoNotTouchRepositories = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .because("controllers call services; services own the transaction and the cache");

    /** The shared kernel may not know what a price or a supplier is. */
    @ArchTest
    static final ArchRule sharedKernelStaysGeneric = noClasses()
            .that().resideInAPackage("com.aatlas.common..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.aatlas.sell..", "com.aatlas.buy..", "com.aatlas.suppliers..",
                    "com.aatlas.catalog..", "com.aatlas.decisions..", "com.aatlas.insights..",
                    "com.aatlas.analytics..", "com.aatlas.rfq..", "com.aatlas.approvals..")
            .because("common is a shared kernel; a dependency the other way makes it a god package");

    /** Every REST controller belongs to a module, never to config or the shared kernel. */
    @ArchTest
    static final ArchRule controllersLiveInModules = classes()
            .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().resideOutsideOfPackages("com.aatlas.common..", "com.aatlas.config..")
            .because("an endpoint belongs to the module that owns its data");
}
