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

    /** A Dockerfile comment line — prose, never a command. */
    private static final Pattern COMMENT = Pattern.compile("(?m)^\\s*#.*$");

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

        for (Path dockerfile : dockerfiles(root)) {
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

    /**
     * Every module whose source an image compiles is copied into it.
     *
     * <p>The sibling test above proves each module's <em>POM</em> is present, which is what
     * dependency resolution needs. Building a module from source needs its <em>tree</em>, and those
     * are different lines in the file. The ledger gained a dependency on {@code libs/auth} and the
     * POM list was already complete, so the catalog test stayed green while {@code docker compose
     * build} failed with "package org.elyonar.fincore.auth does not exist".
     *
     * <p>Which modules a given image compiles is not a list to maintain by hand — it is
     * {@code -pl <module> -am}, which means the module named in the build command plus the
     * transitive closure of its in-reactor dependencies. That closure is computed here from the
     * POMs, so adding a dependency updates the expectation automatically and the only way to fail
     * is to actually forget the COPY.
     */
    @Test
    void every_module_an_image_compiles_has_its_sources_copied() throws IOException {
        Path root = repoRoot();

        for (Path dockerfile : dockerfiles(root)) {
            String content = Files.readString(dockerfile);

            // Comments stripped first: these Dockerfiles explain themselves at length, and one of
            // them describes the build as "`-pl <module> -am`" in prose. Matching that instead of
            // the command is how the first draft of this test collapsed — caught by the canary
            // below, which is the argument for having one.
            String commands = COMMENT.matcher(content).replaceAll("");

            Matcher built = Pattern.compile("-pl\\s+(\\S+)\\s+-am").matcher(commands);
            assertThat(built.find())
                    .as("%s must build with `-pl <module> -am`; the parser found no such command", root.relativize(dockerfile))
                    .isTrue();

            var needed = new TreeSet<String>();
            closure(root, built.group(1), needed);

            // Canary: every image compiles at least its own module and one library, so an empty or
            // singleton closure means the POM walk broke and this test is proving nothing.
            assertThat(needed)
                    .as("the dependency closure for %s collapsed; the POM parser is broken", root.relativize(dockerfile))
                    .hasSizeGreaterThan(1);

            // Aggregators are skipped: `services/core` is packaging `pom` with no source tree, and
            // it lands in the closure because a child names it as `<parent>` — same groupId and
            // artifactId shape as a dependency. "Has a src directory" is the honest test of whether
            // there is anything to copy, and it needs no second list to keep in step.
            var missing =
                    needed.stream()
                            .filter(m -> Files.isDirectory(root.resolve(m).resolve("src")))
                            .filter(m -> !content.contains("COPY " + m + "/src"))
                            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

            assertThat(missing)
                    .as(
                            "%s compiles these modules but never copies their sources, so the build"
                                + " fails inside the image with \"package ... does not exist\" while"
                                + " `mvn install` passes on a developer's disk — the module is right"
                                + " there locally and absent in the container. Add `COPY <module>/src"
                                + " <module>/src` for each.",
                            root.relativize(dockerfile))
                    .isEmpty();
        }
    }

    /** The Dockerfiles that build from the repo root, and so must carry a catalog. */
    private static List<Path> dockerfiles(Path root) {
        return List.of(
                root.resolve("services/ledger/Dockerfile"),
                root.resolve("services/core/app/Dockerfile"),
                // Notification was absent from this list while its image had the same shape and the
                // same failure mode. A guardrail that skips a deployable is not guarding it.
                root.resolve("services/notification/Dockerfile"));
    }

    /** A module and every in-reactor module it depends on, transitively — what {@code -am} builds. */
    private static void closure(Path root, String module, java.util.Set<String> into) throws IOException {
        if (!into.add(module)) {
            return;
        }
        Path pom = root.resolve(module).resolve("pom.xml");
        if (!Files.exists(pom)) {
            return;
        }
        // Only this platform's own artifacts are in the reactor; third-party ones come from the
        // repository and need no source tree.
        Matcher dependency =
                Pattern.compile("<groupId>org\\.elyonar</groupId>\\s*<artifactId>([^<]+)</artifactId>")
                        .matcher(Files.readString(pom));
        while (dependency.find()) {
            String path = modulePath(root, dependency.group(1));
            if (path != null) {
                closure(root, path, into);
            }
        }
    }

    /** Resolves an artifactId to its reactor path, or null when it is the parent rather than a dependency. */
    private static String modulePath(Path root, String artifactId) throws IOException {
        for (String module : reactorModules(root)) {
            Path pom = root.resolve(module).resolve("pom.xml");
            if (!Files.exists(pom)) {
                continue;
            }
            // The first artifactId after the parent block is the module's own.
            Matcher own = Pattern.compile("</parent>.*?<artifactId>([^<]+)</artifactId>", Pattern.DOTALL)
                    .matcher(Files.readString(pom));
            if (own.find() && own.group(1).equals(artifactId)) {
                return module;
            }
        }
        return null;
    }
}
