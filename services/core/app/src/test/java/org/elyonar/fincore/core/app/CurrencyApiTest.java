package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What an institution deals in.
 *
 * <p>The list is the institution's, and the exponent on each row is not. That split is the whole
 * subject here: an exponent is what turns an integer of minor units into an amount, the ledger
 * holds it and makes it immutable once money is recorded against it, and a second copy that could
 * disagree would render the same balance two ways depending on which store a screen happened to
 * read.
 *
 * <p>No ledger runs in these tests, which is the assertion rather than a limitation. Offering a
 * currency has to fail closed when the registry cannot be read — guessing an exponent would write a
 * number that every screen afterwards trusts, and being wrong by one is money out by a factor of
 * ten.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CurrencyApiTest {

    @Autowired private TenantRegistry tenantRegistry;

    @LocalServerPort private int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private UUID tenantId;

    @BeforeEach
    void registerTheTenant() {
        tenantId = UUID.randomUUID();
        tenantRegistry.register(tenantId, "test tenant", "test");
    }

    @DynamicPropertySource
    static void quiet(DynamicPropertyRegistry registry) {
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
        registry.add("fincore.core.outbox.relay.interval-ms", () -> "3600000");
    }

    private HttpRequest.Builder authed(String path, String permissions) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", tenantId.toString())
                .header("X-Dev-Principal", "user:ada")
                .header("X-Dev-Permissions", permissions);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a fresh tenant is offered the seeded currencies, each with its own exponent")
    void the_seeded_list_carries_exponents() {
        HttpResponse<String> list = send(authed("/v1/currencies", "org:read").GET().build());
        assertThat(list.statusCode()).isEqualTo(200);
        // The exponent is the point of the row. Two is the common case and proves nothing; the yen
        // and the dinar are what a hardcoded 2 got wrong.
        assertThat(list.body())
                .contains("\"code\":\"NGN\"")
                .contains("\"code\":\"JPY\"")
                .contains("\"code\":\"KWD\"");
        assertThat(exponentOf(list.body(), "NGN")).isEqualTo(2);
        assertThat(exponentOf(list.body(), "JPY")).isZero();
        assertThat(exponentOf(list.body(), "KWD")).isEqualTo(3);
    }

    @Test
    @DisplayName("offering a currency fails closed when the ledger's registry cannot be read")
    void offering_without_a_registry_invents_nothing() {
        HttpResponse<String> offered =
                send(authed("/v1/currencies", "org:manage")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"TND\",\"name\":\"Tunisian Dinar\"}"))
                                .build());
        assertThat(offered.statusCode()).isNotIn(200, 201);

        // And nothing was written. A half-offered currency would appear on every form and refuse
        // every account opened in it.
        assertThat(send(authed("/v1/currencies", "org:read").GET().build()).body())
                .doesNotContain("\"code\":\"TND\"");
    }

    @Test
    @DisplayName("a body naming neither exponent nor active is well-formed, not a 400")
    void the_request_does_not_demand_what_the_caller_may_not_decide() {
        HttpResponse<String> offered =
                send(authed("/v1/currencies", "org:manage")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"TND\",\"name\":\"Tunisian Dinar\"}"))
                                .build());
        // Reusing the response record here made this a 400 with no field named, because two omitted
        // fields became nulls mapped onto primitives. The caller was right to omit both.
        assertThat(offered.statusCode()).isNotEqualTo(400);
    }

    @Test
    @DisplayName("withdrawing keeps the row so its accounts still render")
    void withdrawing_deactivates_rather_than_deletes() {
        assertThat(send(authed("/v1/currencies/JPY", "org:manage").DELETE().build()).statusCode())
                .isEqualTo(200);

        String after = send(authed("/v1/currencies", "org:read").GET().build()).body();
        assertThat(after).contains("\"code\":\"JPY\"");
        assertThat(exponentOf(after, "JPY")).isZero();
        assertThat(activeOf(after, "JPY")).isFalse();
    }

    @Test
    @DisplayName("reading is open to the institution; changing what it offers is not")
    void changing_the_offering_is_an_institution_decision() {
        assertThat(send(authed("/v1/currencies", "transfers:create").GET().build()).statusCode())
                .isEqualTo(403);
        assertThat(send(authed("/v1/currencies", "org:read")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"USD\"}"))
                                .build())
                        .statusCode())
                .isEqualTo(403);
        assertThat(send(authed("/v1/currencies/NGN", "org:read").DELETE().build()).statusCode())
                .isEqualTo(403);
    }

    /** Reads a field out of the one object in the array carrying {@code code}. */
    private static String fieldOf(String json, String code, String field) {
        int at = json.indexOf("\"code\":\"" + code + "\"");
        assertThat(at).as("%s is in the list", code).isNotNegative();
        int from = json.indexOf("\"" + field + "\":", at) + field.length() + 3;
        int to = from;
        while (to < json.length() && json.charAt(to) != ',' && json.charAt(to) != '}') {
            to++;
        }
        return json.substring(from, to);
    }

    private static int exponentOf(String json, String code) {
        return Integer.parseInt(fieldOf(json, code, "exponent"));
    }

    private static boolean activeOf(String json, String code) {
        return Boolean.parseBoolean(fieldOf(json, code, "active"));
    }
}
