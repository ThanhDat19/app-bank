package vn.banklab.core.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;
import vn.banklab.core.shared.web.ApiErrorResponses;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ModuleArchitectureTest {

    private static final String ROOT = "vn.banklab.core";
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);

    @Test
    void topLevelModulesHaveNoCycles() {
        slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().check(PRODUCTION);
    }

    @Test
    void moduleInternalsAreOnlyAccessedThroughApplicationContracts() {
        classes().should(new ArchCondition<JavaClass>("respect module ownership") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                String from = module(source);
                if (from == null || from.equals("config")) {
                    return;
                }
                source.getDirectDependenciesFromSelf().forEach(dependency -> {
                    JavaClass target = dependency.getTargetClass();
                    String to = module(target);
                    if (to == null || from.equals(to)) {
                        return;
                    }
                    boolean allowed = !from.equals("shared") && !to.equals("config") && (to.equals("shared")
                            || target.getPackageName().equals(ROOT + "." + to + ".application")
                            || target.getPackageName().startsWith(ROOT + "." + to + ".application."));
                    events.add(new SimpleConditionEvent(dependency, allowed, dependency.getDescription()));
                });
            }
        }).check(PRODUCTION);
    }

    @Test
    void domainStaysIndependentOfWebAndPersistence() {
        noClasses().that().resideInAPackage(ROOT + "..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..",
                        "jakarta.persistence..", "..api..", "..web..", "..application..", "..infrastructure..")
                .allowEmptyShould(true).check(PRODUCTION);
    }

    @Test
    void restControllersDeclareSharedErrorContract() {
        classes().that().areAnnotatedWith(RestController.class)
                .should().resideInAPackage(ROOT + "..api..")
                .andShould().beAnnotatedWith(ApiErrorResponses.class).check(PRODUCTION);
    }

    private static String module(JavaClass type) {
        String packageName = type.getPackageName();
        if (!packageName.startsWith(ROOT + ".")) {
            return null;
        }
        return packageName.substring(ROOT.length() + 1).split("\\.")[0];
    }
}
