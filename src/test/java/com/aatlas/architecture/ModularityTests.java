package com.aatlas.architecture;

import com.aatlas.AatlasApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * The module boundaries, checked at build time.
 *
 * <p>This is what makes "modular monolith" mean something. Without it, the sixteen
 * packages are a filing convention that erodes the first time someone reaches into
 * another module's internals under deadline; with it, that reach fails the build.
 * The clean seams the blueprint wants to split into services later only stay clean if
 * something enforces them today.
 */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(AatlasApplication.class);

    @Test
    void modulesRespectTheirBoundaries() {
        MODULES.verify();
    }

    @Test
    void listModules() {
        MODULES.forEach(System.out::println);
    }

    /**
     * Writes PlantUML component diagrams and an module canvas per module into
     * {@code target/spring-modulith-docs}. Cheap, always current, and far more honest
     * than an architecture diagram maintained by hand.
     */
    @Test
    void writeDocumentation() {
        new Documenter(MODULES)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();
    }
}
