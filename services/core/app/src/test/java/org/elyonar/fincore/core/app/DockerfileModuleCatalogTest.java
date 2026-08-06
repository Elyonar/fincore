package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every Maven module the reactor declares is copied by every Dockerfile.
 *
 * <p>Written after this broke a third time. Both Dockerfiles copy the root {@code pom.xml} and then
 * a hand-maintained list of module POMs, so that dependency resolution can be cached in its own
 * layer. That is a good build, and it has one failure mode: adding a module to the reactor without
 * adding it to the list produces <em>"Child module … does not exist"</em>, and only when someone
 * builds an image.
 *
 * <p>Nothing else catches it. {@code mvn install} passes — the module is right there on disk. The
 * test suite passes. The break is invisible until a deployment, which is the worst place to find
 * it and the furthest from whoever added the module.
 *
 * <p>Deliberately reads the files rather than running Docker. A test that shelled out to
 * {@code docker build} would be slow, would need a daemon, and would be skipped in exactly the
 * environments that most need it — and the defect is a missing line in a text file, which is
 * something a string comparison can prove.
 */
class DockerfileModuleCatalogTest {

    private static final Pattern MODULE = Pattern.compile("<module>([^<]+)</module>");

    private static Path repoRoot() {
        // Surefire runs with the module directory as its working directory; walk up to the root.
        Path candidate = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && candidate != null; up++) {
            if (Files.exists(candidate.resolve("pom.xml")) && Files.isDirectory(candidate.resolve("services"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("cannot locate the repository root from " + Path.of("").toAbsolutePath());
    }

    /** Every module path in the reactor, aggregators included, resolved relative to the root. */
    private static List<String> reactorModules(Path root) throws IOException {
        List<String> found = new ArrayList<>();
        collect(root, "", found);
        return found;
    }

    private static void collect(Path root, String prefix, List<String> into) throws IOException {
        Path pom = root.resolve(prefix.isEmpty() ? "pom.xml" : prefix + "/pom.xml");
        if (!Files.exists(pom)) {
            return;
        }
        Matcher matcher = MODULE.matcher(Files.readString(pom));
        while (matcher.find()) {
            String path = prefix.isEmpty() ? matcher.group(1) : prefix + "/" + matcher.group(1);
            into.add(path);
            // Aggregators nest — services/core declares customer, product, orchestration and app,
            // and each of those needs copying too.
            collect(root, path, into);
        }
    }

    @Test
    void every_reactor_module_is_copied_by_every_dockerfile() throws IOException {
        Path root = repoRoot();
        List<String> modules = reactorModules(root);

        // Canary. If the POM parser stops matching, every assertion below passes against an empty
        // list and this test becomes a decoration that reports success.
        assertThat(modules)
                .as("the reactor must declare modules; the parser found none")
                .contains("libs/auth", "services/ledger", "services/core/app");

        List<Path> dockerfiles =
                List.of(
                        root.resolve("services/ledger/Dockerfile"),
                        root.resolve("services/core/app/Dockerfile"));

        for (Path dockerfile : dockerfiles) {
            assertThat(dockerfile).as("Dockerfile is missing entirely").exists();
            String content = Files.readString(dockerfile);

            var missing = new TreeSet<String>();
            for (String module : modules) {
                if (!content.contains("COPY " + module + "/pom.xml")) {
                    missing.add(module);
                }
            }

            assertThat(missing)
                    .as(
                            "%s copies the root pom.xml, so Maven reads the whole reactor inside the"
                                + " image and fails with \"Child module ... does not exist\" for any"
                                + " module whose pom.xml was not copied. Add a COPY line for each"
                                + " module listed here. Found by a text comparison rather than by a"
                                + " failed deployment, which is the entire point.",
                            root.relativize(dockerfile))
                    .isEmpty();
        }
    }
}
