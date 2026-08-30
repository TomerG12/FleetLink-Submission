package io.github.tomerg12.fleetlink.shared.rmi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies that shared production code and its runtime remain free of UI and persistence stacks.
 */
class SharedDependencyBoundaryTest {

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "import javafx.",
            "import jakarta.persistence.",
            "import org.hibernate.",
            "import org.h2.",
            "import com.h2database.");

    /**
     * Proves forbidden framework types are absent from the shared test runtime classpath.
     */
    @Test
    void sharedRuntimeHasNoUiOrPersistenceFrameworks() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("javafx.scene.Node"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("jakarta.persistence.Entity"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.hibernate.Session"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.h2.Driver"));
    }

    /**
     * Scans shared production imports so forbidden framework leakage fails close to its source.
     *
     * @throws IOException if production sources cannot be read
     */
    @Test
    void sharedProductionSourcesHaveNoForbiddenImports() throws IOException {
        Path sourceRoot = locateSourceRoot();
        assertTrue(Files.isDirectory(sourceRoot));

        try (var paths = Files.walk(sourceRoot)) {
            List<Path> javaFiles = paths.filter(path -> path.toString().endsWith(".java")).toList();
            assertFalse(javaFiles.isEmpty());
            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                for (String forbiddenImport : FORBIDDEN_IMPORTS) {
                    assertFalse(source.contains(forbiddenImport),
                            javaFile + " contains " + forbiddenImport);
                }
            }
        }
    }

    /**
     * Locates shared production sources for both module-only and root-reactor test execution.
     *
     * @return the existing shared production source directory
     */
    private static Path locateSourceRoot() {
        Path basedir = Path.of(System.getProperty("basedir", "."));
        Path moduleSource = basedir.resolve("src/main/java");
        if (Files.isDirectory(moduleSource)) {
            return moduleSource;
        }
        return basedir.resolve("shared/src/main/java");
    }
}
