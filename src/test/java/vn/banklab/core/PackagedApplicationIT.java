package vn.banklab.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests from target/classes can pass even when packaging omitted a module. Verify the shipped artifact.
 */
class PackagedApplicationIT {
    @Test
    void bootJarContainsEveryProductionTopLevelClassAndMigrations() throws Exception {
        Path sources = Path.of(System.getProperty("bank.source-directory"));
        try (var jar = new JarFile(System.getProperty("bank.packaged-jar"));
             var files = Files.walk(sources)) {
            var expected = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("package-info.java"))
                    .filter(path -> !path.getFileName().toString().equals("module-info.java"))
                    .map(path -> "BOOT-INF/classes/" + sources.relativize(path).toString()
                            .replace('\\', '/').replaceFirst("\\.java$", ".class")).toList();
            assertThat(expected).isNotEmpty();
            for (String entry : expected) {
                assertThat(jar.getJarEntry(entry)).as("Packaged production class %s", entry).isNotNull();
            }
            assertThat(jar.getJarEntry("BOOT-INF/classes/db/migration/V3__create_accounts_and_lifecycle_history.sql")).isNotNull();
            assertThat(jar.getJarEntry("BOOT-INF/classes/db/migration/V4__create_double_entry_ledger.sql")).isNotNull();
            assertThat(jar.getJarEntry("BOOT-INF/classes/db/migration/V5__create_idempotent_cash_ins.sql")).isNotNull();
            assertThat(jar.getJarEntry("BOOT-INF/classes/db/migration/V6__create_idempotent_internal_transfers.sql")).isNotNull();
            assertThat(jar.getJarEntry("BOOT-INF/classes/db/migration/V7__create_immutable_full_reversals.sql")).isNotNull();
            assertThat(jar.stream().map(entry -> entry.getName()).anyMatch(name -> name.contains("SecurityProbe"))).isFalse();
        }
    }
}
