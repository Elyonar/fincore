package org.elyonar.fincore.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The HTTP surface, and the document that describes it.
 *
 * <p>The last test here is the important one. Core's {@code api.md} was stamped AGREED listing
 * sixteen endpoints of which six existed, and nothing noticed — a document read as a description of
 * a running system while being a description of an intention. This is the bidirectional check that
 * makes that impossible: every documented route must exist, and every route must be documented.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("api — the surface, and the document that describes it")
class ApiTest {

    /** Matches a documented row: a fenced `METHOD /v1/path` at the start of a table cell. */
    private static final Pattern DOCUMENTED = Pattern.compile("\\|\\s*`(GET|POST|PUT|PATCH|DELETE)\\s+(/v1/[^`\\s]*)`");

    @LocalServerPort private int port;
    // Qualified by name: the actuator contributes a second RequestMappingHandlerMapping, and an
    // unqualified injection fails at context load with a message about two beans rather than
    // anything to do with routes.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private final HttpClient http = HttpClient.newHttpClient();
    private UUID tenant;

    @BeforeEach
    void freshTenant() {
        tenant = UUID.randomUUID();
    }

    // ------------------------------------------------------------------ templates

    @Test
    @DisplayName("a template is created as a draft and published with attribution and a measurement")
    void a_template_is_drafted_then_published() {
        String created = send(as("/v1/templates", "templates:create")
                        .POST(body("{\"templateKey\":\"debit.alert\",\"channel\":\"SMS\",\"locale\":\"en\","
                                + "\"parts\":{\"body\":\"Debit of {{amountMinor}}\"}}"))
                        .build())
                .body();

        assertThat(created).contains("\"status\":\"DRAFT\"");
        String id = field(created, "id");

        String published = send(as("/v1/templates/" + id + "/versions/1/publish", "templates:publish")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build())
                .body();

        assertThat(published).contains("\"status\":\"PUBLISHED\"").contains("\"units\":1");
        // Measured at publish, so a template over its channel's cap is refused while someone is
        // still editing it rather than discovered on the bill.
        assertThat(published).contains("\"publishedBy\"").doesNotContain("\"publishedBy\":null");
    }

    @Test
    @DisplayName("an email template with no subject is refused, because its channel requires one")
    void a_channel_requires_its_parts() {
        HttpResponse<String> response = send(as("/v1/templates", "templates:create")
                .POST(body("{\"templateKey\":\"x\",\"channel\":\"EMAIL\",\"locale\":\"en\","
                        + "\"parts\":{\"body\":\"hello\"}}"))
                .build());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("TEMPLATE_PART_MISSING").contains("subject");
    }

    @Test
    @DisplayName("an unknown channel is refused by name")
    void an_unknown_channel_is_refused() {
        HttpResponse<String> response = send(as("/v1/templates", "templates:create")
                .POST(body("{\"templateKey\":\"x\",\"channel\":\"CARRIER_PIGEON\",\"locale\":\"en\","
                        + "\"parts\":{\"body\":\"hello\"}}"))
                .build());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("UNKNOWN_CHANNEL");
    }

    @Test
    @DisplayName("a published version cannot be published again")
    void publishing_twice_conflicts() {
        String created = send(as("/v1/templates", "templates:create")
                        .POST(body("{\"templateKey\":\"twice\",\"channel\":\"SMS\",\"locale\":\"en\","
                                + "\"parts\":{\"body\":\"hi\"}}"))
                        .build())
                .body();
        String id = field(created, "id");
        send(as("/v1/templates/" + id + "/versions/1/publish", "templates:publish")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());

        HttpResponse<String> again = send(as("/v1/templates/" + id + "/versions/1/publish", "templates:publish")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());

        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("TEMPLATE_ALREADY_PUBLISHED");
    }

    // ------------------------------------------------------------------ policy

    @Test
    @DisplayName("a policy is set and read back")
    void a_policy_round_trips() {
        HttpResponse<String> set = send(as("/v1/policy/TRANSACTIONAL", "policy:write")
                .PUT(body("{\"channels\":[\"SMS\",\"EMAIL\"],\"quietFrom\":\"21:00\",\"quietTo\":\"07:00\"}"))
                .build());

        assertThat(set.statusCode()).isEqualTo(200);
        assertThat(send(as("/v1/policy", "notifications:read").GET().build()).body())
                .contains("TRANSACTIONAL")
                .contains("SMS");
    }

    @Test
    @DisplayName("a quiet window with one end is refused")
    void half_a_window_is_refused() {
        HttpResponse<String> response = send(as("/v1/policy/MARKETING", "policy:write")
                .PUT(body("{\"channels\":[\"SMS\"],\"quietFrom\":\"21:00\"}"))
                .build());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("POLICY_INCOMPLETE");
    }

    // ------------------------------------------------------------------ reads

    @Test
    @DisplayName("the suppression read never exposes an address")
    void reads_do_not_leak_pii() {
        assertThat(send(as("/v1/suppressions", "notifications:read").GET().build()).statusCode())
                .isEqualTo(200);
        assertThat(send(as("/v1/deliveries", "notifications:read").GET().build()).body())
                .doesNotContain("recipient_address");
    }

    @Test
    @DisplayName("every endpoint denies by default")
    void the_surface_denies_by_default() {
        assertThat(send(as("/v1/templates", "something:else").GET().build()).statusCode()).isEqualTo(403);
        assertThat(send(as("/v1/policy", "something:else").GET().build()).statusCode()).isEqualTo(403);
        assertThat(send(as("/v1/suppressions", "something:else").GET().build()).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("the service identity and health answer without a token")
    void the_open_endpoints_are_open() {
        assertThat(send(HttpRequest.newBuilder(URI.create(base() + "/")).GET().build()).statusCode())
                .isEqualTo(200);
        assertThat(send(HttpRequest.newBuilder(URI.create(base() + "/actuator/health")).GET().build())
                        .statusCode())
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ the document

    @Test
    @DisplayName("every documented route exists, and every route is documented")
    void the_document_and_the_code_agree() throws Exception {
        Set<String> documented = new TreeSet<>();
        Matcher matcher = DOCUMENTED.matcher(Files.readString(apiDoc()));
        while (matcher.find()) {
            documented.add(matcher.group(1) + " " + normalise(matcher.group(2)));
        }

        Set<String> served = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> served.addAll(routes(info)));

        // A positive spot-check cannot find an absence, which is why this is a set comparison in
        // both directions rather than a list of paths someone remembered to add.
        assertThat(documented).as("routes documented but not served").isSubsetOf(served);
        assertThat(served).as("routes served but not documented").isSubsetOf(documented);
    }

    private static List<String> routes(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() == null) {
            return List.of();
        }
        return info.getPathPatternsCondition().getPatterns().stream()
                .map(pattern -> pattern.getPatternString())
                .filter(path -> path.startsWith("/v1/"))
                .flatMap(path -> info.getMethodsCondition().getMethods().stream()
                        .map(method -> method.name() + " " + normalise(path)))
                .toList();
    }

    /** Path variables compare by position, not by name: {@code {id}} and {@code {templateId}} are one route. */
    private static String normalise(String path) {
        return path.replaceAll("\\{[^}]*}", "{}");
    }

    private static Path apiDoc() {
        for (String candidate : new String[] {"docs/api.md", "services/notification/docs/api.md"}) {
            Path path = Path.of(candidate);
            if (Files.exists(path)) {
                return path;
            }
        }
        throw new IllegalStateException("cannot find docs/api.md from " + Path.of("").toAbsolutePath());
    }

    // ------------------------------------------------------------------ harness

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpRequest.Builder as(String path, String permissions) {
        return HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", tenant.toString())
                .header("X-Dev-Principal", "user:admin")
                .header("X-Dev-Permissions", permissions);
    }

    private static HttpRequest.BodyPublisher body(String json) {
        return HttpRequest.BodyPublishers.ofString(json);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String field(String json, String name) {
        int at = json.indexOf("\"" + name + "\":\"");
        if (at < 0) {
            return null;
        }
        at += name.length() + 4;
        return json.substring(at, json.indexOf('"', at));
    }
}
