package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every endpoint {@code api.md} documents exists, and every endpoint that exists is documented.
 *
 * <p>Written because the opposite was true and nothing noticed. {@code api.md} was stamped AGREED
 * and listed sixteen endpoints; six were built. Customer had no HTTP surface at all, Product had
 * none, and deposits, withdrawals and reversal — all three named in the CHANGELOG's own v1 scope —
 * existed as tested services with no route to reach them. The document read as a description of a
 * running system and was a description of an intention.
 *
 * <p>{@code ServiceSurfaceTest} did not catch it, and could not: it asserts that the OpenAPI
 * document <em>contains</em> two known paths. A positive spot-check cannot find an absence. This is
 * the bidirectional version, modelled on the Ledger's {@code ErrorCodeCatalogTest} — the same idea
 * applied to routes instead of error codes, and the reason that suite has not drifted.
 *
 * <p>This is deliberately preferred over status markers in the document. A marker saying "not yet
 * built" is a fact maintained by hand, which is the same category of thing that went stale in the
 * first place; a failing test is not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSurfaceCatalogTest {

    /** Matches a documented row: a fenced `METHOD /v1/path` at the start of a table cell. */
    private static final Pattern DOCUMENTED =
            Pattern.compile("\\|\\s*`(GET|POST|PUT|PATCH|DELETE)\\s+(/v1/[^`\\s]*)`");

    @LocalServerPort private int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void quiet(DynamicPropertyRegistry registry) {
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
        registry.add("fincore.core.outbox.relay.interval-ms", () -> "3600000");
    }

    /**
     * Path variables are compared by position, not by name.
     *
     * <p>{@code api.md} writes {@code /versions/{v}/publish} where the code writes
     * {@code {version}}. Both name the same route, and a test that failed over the spelling of a
     * placeholder would be reporting a documentation style difference as a missing endpoint — which
     * is how a useful check earns itself a mute.
     */
    private static String normalise(String path) {
        return path.replaceAll("\\{[^}]*}", "{}");
    }

    private static Path apiDoc() {
        // Surefire runs with the module directory as its working directory.
        for (String candidate : new String[] {"../docs/api.md", "services/core/docs/api.md", "docs/api.md"}) {
            Path path = Path.of(candidate);
            if (Files.exists(path)) {
                return path;
            }
        }
        throw new IllegalStateException("cannot find services/core/docs/api.md from " + Path.of("").toAbsolutePath());
    }

    private Set<String> documentedEndpoints() throws IOException {
        Set<String> found = new TreeSet<>();
        Matcher matcher = DOCUMENTED.matcher(Files.readString(apiDoc()));
        while (matcher.find()) {
            found.add(matcher.group(1) + " " + normalise(matcher.group(2)));
        }
        return found;
    }

    private Set<String> builtEndpoints() throws Exception {
        HttpResponse<String> spec =
                http.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(spec.statusCode()).as("the generated OpenAPI document must be reachable").isEqualTo(200);

        JsonNode paths = JsonMapper.builder().build().readTree(spec.body()).get("paths");
        Set<String> found = new TreeSet<>();
        for (String path : paths.propertyNames()) {
            // Only the versioned API. `/`, actuator and the docs themselves are infrastructure,
            // not the contract a channel integrates against.
            if (!path.startsWith("/v1/")) {
                continue;
            }
            for (String method : paths.get(path).propertyNames()) {
                found.add(method.toUpperCase(java.util.Locale.ROOT) + " " + normalise(path));
            }
        }
        return found;
    }

    @Test
    void every_documented_endpoint_is_built() throws Exception {
        Set<String> documented = documentedEndpoints();
        Set<String> built = builtEndpoints();

        // The canary. If the regex stops matching the table, both directions pass vacuously and
        // this test becomes a decoration — the precise failure it exists to prevent.
        assertThat(documented)
                .as("api.md must contain a table of `METHOD /v1/...` endpoints; the parser found none")
                .isNotEmpty();

        List<String> promisedButAbsent = documented.stream().filter(e -> !built.contains(e)).toList();

        assertThat(promisedButAbsent)
                .as(
                        "api.md documents these endpoints but nothing serves them. A documented "
                            + "endpoint that does not exist is worse than an undocumented one: an "
                            + "integrator plans against it.")
                .isEmpty();
    }

    @Test
    void every_built_endpoint_is_documented() throws Exception {
        Set<String> documented = documentedEndpoints();
        Set<String> built = builtEndpoints();

        assertThat(built).as("no /v1 endpoints found in the generated OpenAPI document").isNotEmpty();

        List<String> servedButUndocumented = built.stream().filter(e -> !documented.contains(e)).toList();

        assertThat(servedButUndocumented)
                .as(
                        "these endpoints are served but appear nowhere in api.md. An undocumented "
                            + "route is an unreviewed one — nobody agreed its shape, its permission "
                            + "or its failure modes.")
                .isEmpty();
    }
}
