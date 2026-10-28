package com.erp.workorder.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The four-layer rule, checked over the SOURCE CODE so it holds without
 * starting the application and without anyone remembering to run it by
 * hand:
 *
 * <ol>
 *   <li>The domain imports NO framework: the state machine, the aggregate
 *   and its value objects must survive a change of web or persistence
 *   framework untouched.</li>
 *   <li>The application does not import infrastructure: the use cases know
 *   the ports of the domain, never how the adapters are built.</li>
 *   <li>No persistence adapter declares its own {@code @Transactional}
 *   (readOnly=true allowed): the adapters JOIN the transaction the
 *   application layer opens through the UnitOfWork port — a write
 *   confirmed per adapter would break the atomicity of a transition.</li>
 * </ol>
 *
 * <p>Ver docs/doc-estado-implementacion.md hallazgos P0 5.3 y P0 5.8, y la
 * decision D7 of the change design.
 */
class FourLayerArchitectureTest {

    private static final Path DOMAIN = Paths.get("src/main/java/com/erp/workorder/domain");
    private static final Path APPLICATION = Paths.get("src/main/java/com/erp/workorder/application");
    private static final Path ADAPTERS = Paths.get(
            "src/main/java/com/erp/workorder/infrastructure/persistence/adapter");

    /** Any framework the domain must never see. */
    private static final Pattern DOMAIN_FRAMEWORK_IMPORT = Pattern.compile(
            "^import\\s+(org\\.springframework|jakarta\\.|javax\\.|io\\.resilience4j|com\\.zaxxer|org\\.hibernate)");

    /** The dependency direction the application layer must never break. */
    private static final Pattern APPLICATION_INFRASTRUCTURE_IMPORT = Pattern.compile(
            "^import\\s+com\\.erp\\.workorder\\.infrastructure");

    /** Any own-transaction demarcation in an adapter, readOnly bound included below. */
    private static final Pattern TRANSACTIONAL = Pattern.compile("@Transactional\\b");

    /** The only demarcation an adapter may declare, on reads. */
    private static final Pattern READ_ONLY = Pattern.compile(
            "@Transactional\\s*\\(\\s*readOnly\\s*=\\s*true\\s*\\)");

    @Test
    void theDomainImportsNoFramework() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(DOMAIN)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (DOMAIN_FRAMEWORK_IMPORT.matcher(line).matches()) {
                    violations.add(fileName(file) + ":" + (i + 1) + "  " + line);
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("The domain is free of frameworks: the state machine, the aggregate and the "
                    + "value objects must not know Spring, JPA or any transport.\n"
                    + String.join("\n", violations));
        }
    }

    @Test
    void theApplicationDoesNotImportInfrastructure() throws IOException {
        if (!Files.isDirectory(APPLICATION)) {
            return; // No use case yet; the rule is trivially held.
        }
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(APPLICATION)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (APPLICATION_INFRASTRUCTURE_IMPORT.matcher(line).matches()) {
                    violations.add(fileName(file) + ":" + (i + 1) + "  " + line);
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("The application layer depends on the ports of the domain, never on the "
                    + "adapters: a use case does not know HOW something is done, only THAT it is.\n"
                    + String.join("\n", violations));
        }
    }

    @Test
    void noPersistenceAdapterDeclaresItsOwnDemarcation() throws IOException {
        if (!Files.isDirectory(ADAPTERS)) {
            return; // No business adapter yet; the rule is trivially held.
        }
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(ADAPTERS)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!TRANSACTIONAL.matcher(line).find() || READ_ONLY.matcher(line).find()) {
                    continue;
                }
                violations.add(fileName(file) + ":" + (i + 1) + "  " + line.trim());
            }
        }
        if (!violations.isEmpty()) {
            fail("The persistence adapters must not declare their own demarcation: they join the "
                    + "transaction the application layer opens through UnitOfWork.\n"
                    + "A per-adapter @Transactional confirms every write separately and breaks the "
                    + "atomicity of the transition (status, history and event).\n"
                    + String.join("\n", violations));
        }
    }

    private List<Path> javaFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            fail("Cannot find the directory " + directory.toAbsolutePath()
                    + ". The test must run from the module root.");
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return files
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private String fileName(Path file) {
        return Paths.get("").toAbsolutePath().relativize(file.toAbsolutePath()).toString();
    }
}
