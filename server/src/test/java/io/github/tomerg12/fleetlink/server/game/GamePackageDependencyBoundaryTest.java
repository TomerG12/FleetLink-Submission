package io.github.tomerg12.fleetlink.server.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Protects the in-memory game domain from completion handoff and persistence dependencies.
 */
class GamePackageDependencyBoundaryTest {

    private static final List<String> FORBIDDEN_PACKAGES = List.of(
            "io.github.tomerg12.fleetlink.server.completion",
            "io.github.tomerg12.fleetlink.server.persistence");

    /**
     * Scans every production game source for forbidden completion or persistence references.
     *
     * @throws IOException if production game sources cannot be read
     */
    @Test
    void gameProductionSourcesHaveNoCompletionOrPersistenceDependencies() throws IOException {
        Path sourceRoot = locateGameSourceRoot();
        assertTrue(Files.isDirectory(sourceRoot));
        try (var paths = Files.walk(sourceRoot)) {
            List<Path> javaFiles = paths.filter(path -> path.toString().endsWith(".java")).toList();
            assertFalse(javaFiles.isEmpty());
            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                for (String forbiddenPackage : FORBIDDEN_PACKAGES) {
                    assertFalse(source.contains(forbiddenPackage),
                            javaFile + " contains " + forbiddenPackage);
                }
            }
        }
    }

    /**
     * Resolves the game source directory for module-only and root-reactor execution.
     *
     * @return existing production game source directory
     */
    private static Path locateGameSourceRoot() {
        Path basedir = Path.of(System.getProperty("basedir", "."));
        Path moduleRoot = basedir.resolve(
                "src/main/java/io/github/tomerg12/fleetlink/server/game");
        if (Files.isDirectory(moduleRoot)) {
            return moduleRoot;
        }
        return basedir.resolve(
                "server/src/main/java/io/github/tomerg12/fleetlink/server/game");
    }
}
