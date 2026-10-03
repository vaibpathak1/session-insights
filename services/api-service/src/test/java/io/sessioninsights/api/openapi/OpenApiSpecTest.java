package io.sessioninsights.api.openapi;

import io.sessioninsights.api.ApiIntegrationTest;
import io.sessioninsights.common.wire.WireJson;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 5.7: {@code docs/api/openapi.yaml} matches the controllers. springdoc is on the test
 * classpath only: it generates the document from the running application, and the committed
 * spec must describe the same operations, parameters, responses and schemas. Human-written
 * prose (description, summary, examples, info, servers, tags) is not compared.
 * <p>
 * The committed spec may document more than springdoc can derive (error responses, the Basic
 * security scheme); everything springdoc derives must be there unchanged.
 * <p>
 * To see the generated document: {@code -Dopenapi.write=true} writes it to
 * {@code target/openapi.generated.json}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiSpecTest extends ApiIntegrationTest {

    private static final Set<String> PROSE = Set.of("description", "summary", "example", "examples", "info", "servers",
            "tags", "externalDocs", "title");

    static {
        // swagger-core introspects with Jackson 2 and does not know Jackson 3's JsonNode: it is a
        // free-form JSON value; a replay chunk streams the rrweb event array (test scope only)
        SpringDocUtils.getConfig()
                .replaceWithSchema(JsonNode.class, new ObjectSchema())
                .replaceWithSchema(StreamingResponseBody.class, new ArraySchema().items(new ObjectSchema()));
    }

    @LocalServerPort
    int port;

    @Test
    void committedSpecMatchesTheControllers() throws Exception {
        String auth = "Basic " + Base64.getEncoder().encodeToString(
                ("admin@example.com:" + DEV_PASSWORD).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).header("Authorization", auth).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        if (Boolean.getBoolean("openapi.write")) {
            Files.writeString(Path.of("target", "openapi.generated.json"), response.body());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> generated = WireJson.mapper().readValue(response.body(), Map.class);
        Map<String, Object> committed = new Yaml().load(Files.readString(repoRoot().resolve("docs/api/openapi.yaml")));

        assertContains("paths", normalize(committed.get("paths")), normalize(generated.get("paths")));
        assertContains("components.schemas", normalize(((Map<?, ?>) committed.get("components")).get("schemas")),
                normalize(((Map<?, ?>) generated.get("components")).get("schemas")));
        // and nothing documented that the application does not serve
        assertThat(((Map<?, ?>) normalize(committed.get("paths"))).keySet())
                .isEqualTo(((Map<?, ?>) normalize(generated.get("paths"))).keySet());
    }

    /**
     * Every key of {@code generated} is in {@code committed} with an equal value (maps compared
     * recursively); lists and scalars must be equal. Extra committed keys are allowed.
     */
    static void assertContains(String path, Object committed, Object generated) {
        if (generated instanceof Map<?, ?> g && committed instanceof Map<?, ?> c) {
            g.forEach((k, v) -> {
                assertThat(c.containsKey(k)).as(path + " has " + k).isTrue();
                assertContains(path + "." + k, c.get(k), v);
            });
        } else {
            assertThat(committed).as(path).isEqualTo(generated);
        }
    }

    /** Sorted maps without prose; numbers as text so YAML and JSON integers compare equal. */
    static Object normalize(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new TreeMap<>();
            map.forEach((k, v) -> {
                if (!PROSE.contains(String.valueOf(k))) {
                    out.put(String.valueOf(k), normalize(v));
                }
            });
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(normalize(v)));
            return out;
        }
        return node == null ? null : String.valueOf(node);
    }

}
