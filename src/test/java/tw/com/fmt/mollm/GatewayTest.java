package tw.com.fmt.mollm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real loopback HTTP integration checks; no API key or paid model is needed. */
public final class GatewayTest {
    private static int checks;
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        check(Gateway.normalizeUrl("localhost:4000/").equals("http://localhost:4000/v1"), "URL default version");
        check(Gateway.normalizeUrl("https://example.test/proxy/v2/").equals("https://example.test/proxy/v2"), "URL preserves prefix and version");
        for (String invalid : List.of("https://u:secret@example.test", "https://example.test?key=secret", "https://example.test/#token", "ftp://example.test", "")) {
            try { Gateway.normalizeUrl(invalid); throw new AssertionError("Invalid URL accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        var authorized = new AtomicBoolean();
        var received = new AtomicReference<JsonObject>();
        var streamingMode = new AtomicReference<>("normal");
        var waiting = new AtomicReference<>(new CountDownLatch(1));
        server.createContext("/proxy/v1/models", exchange -> {
            authorized.set("Bearer test-key".equals(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] bytes = "{\"data\":[{\"id\":\"alpha\"},{\"id\":\"embedding\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/proxy/model_group/info", exchange -> {
            byte[] bytes = ("{\"data\":[{\"model_group\":\"alpha\",\"mode\":\"chat\",\"max_output_tokens\":16,"
                    + "\"supported_openai_params\":[\"reasoning_effort\",\"thinking\"]},{\"model_group\":\"embedding\",\"mode\":\"embedding\"},"
                    + "{\"model_group\":\"not-authorized\",\"mode\":\"chat\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/proxy/key/info", exchange -> {
            byte[] bytes = "{\"error\":\"forbidden test-key\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/proxy/user/daily/activity", exchange -> {
            byte[] bytes = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/proxy/v1/chat/completions", exchange -> {
            received.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                if (streamingMode.get().equals("wait")) {
                    out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8)); out.flush(); waiting.get().countDown();
                    try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                } else if (streamingMode.get().equals("malformed")) {
                    out.write("data: not-json\n\n".getBytes(StandardCharsets.UTF_8));
                } else if (streamingMode.get().equals("truncated")) {
                    out.write("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    String sse = ": comment\r\n\r\ndata: {\"choices\":[{\"delta\":{\"reasoning_content\":\"想\"}}]}\r\n\r\n"
                            + "data: {\"choices\":[{\"delta\":{\"reasoning\":\"法\"}}]}\n\n"
                            + "data: {\"choices\":\n" + "data: [{\"delta\":{\"content\":\"你好 🌏\"}}]}\n\n"
                            + "data: {\"choices\":[],\"usage\":{\"total_tokens\":4}}\n\n" + "data: [DONE]\n\n";
                    byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
                    for (byte value : bytes) { out.write(value); out.flush(); }
                }
            } catch (IOException expectedOnCancellation) { /* Client deliberately disconnects. */ }
            finally { exchange.close(); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/proxy";
            var api = new Gateway(base, "test-key", 1000);
            var models = api.models();
            check(authorized.get(), "Bearer authentication");
            check(models.size() == 1 && models.getFirst().id().equals("alpha"), "Authorized models only; exclude embedding");
            var config = new MollmSettings.Server();
            var override = new MollmSettings.ModelOverride(); override.reasoningEffort = "high"; override.thinkingBudgetTokens = 8;
            config.overrides.put("alpha", override);
            var history = List.of(new Gateway.Message("user", "測試"));
            var body = Gateway.request(models.getFirst(), history, config);
            check(body.get("max_tokens").getAsInt() == 16, "Gateway output limit");
            check(body.get("reasoning_effort").getAsString().equals("high"), "Supported reasoning forwarded");
            check(body.getAsJsonObject("thinking").get("budget_tokens").getAsInt() == 8, "Supported thinking forwarded");
            var unknown = Gateway.request(new Gateway.Model("alpha", new JsonObject()), history, config);
            check(!unknown.has("reasoning_effort") && !unknown.has("thinking"), "Unsupported reasoning filtered");
            var reasoningInfo = JsonParser.parseString("{\"supported_openai_params\":[\"max_completion_tokens\"]}").getAsJsonObject();
            var reasoningBody = Gateway.request(new Gateway.Model("alpha", reasoningInfo), history, config);
            check(reasoningBody.has("max_completion_tokens") && !reasoningBody.has("max_tokens"), "Reasoning model output parameter");
            var answer = new StringBuilder(); var reasoning = new StringBuilder();
            try (var call = new Gateway.Call()) {
                api.stream(body, call, (kind, text) -> (kind.equals("text") ? answer : reasoning).append(text));
            }
            check(answer.toString().equals("你好 🌏"), "Fragmented UTF-8, LF/CRLF, multiline SSE");
            check(reasoning.toString().equals("想法"), "Both reasoning fields");
            check(received.get().get("model").getAsString().equals("alpha"), "Chat endpoint and JSON request");
            var usage = api.usage();
            check(usage.days().isEmpty() && usage.warnings().size() == 1, "Optional management permission failure");
            check(!usage.warnings().getFirst().contains("test-key"), "API key redacted from error");
            for (String mode : List.of("malformed", "truncated")) {
                streamingMode.set(mode);
                try (var call = new Gateway.Call()) {
                    try { api.stream(body, call, (kind, text) -> { }); throw new AssertionError("Bad stream accepted: " + mode); }
                    catch (IOException expected) { checks++; }
                }
            }
            streamingMode.set("wait");
            try (var call = new Gateway.Call()) {
                long start = System.nanoTime();
                try { api.stream(body, call, (kind, text) -> { }); throw new AssertionError("Idle timeout missing"); }
                catch (java.net.SocketTimeoutException expected) {
                    check(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2500, "Read idle timeout");
                }
            }
            var cancellationApi = new Gateway(base, "test-key", 30_000);
            waiting.set(new CountDownLatch(1));
            try (var call = new Gateway.Call()) {
                var result = executor.submit(() -> {
                    try { cancellationApi.stream(body, call, (kind, text) -> { }); }
                    catch (IOException | java.util.concurrent.CancellationException expected) { return true; }
                    return call.cancelled();
                });
                check(waiting.get().await(1, TimeUnit.SECONDS), "Cancellation test connected");
                call.cancel();
                // The test server deliberately holds the response open for 3 seconds.
                // HttpURLConnection may finish its blocked read only when the server writes/closes.
                check(result.get(6, TimeUnit.SECONDS), "Cancellation closes active read");
            }
            SwingCheck.run();
            var state = new MollmSettings.Data();
            state.usageRefreshSeconds = 180;
            config.label = "測試閘道"; config.models = List.of("alpha"); state.servers.add(config);
            var xml = com.intellij.util.xmlb.XmlSerializer.serialize(state);
            var restored = com.intellij.util.xmlb.XmlSerializer.deserialize(xml, MollmSettings.Data.class);
            check(restored.servers.getFirst().label.equals("測試閘道") && restored.servers.getFirst().models.equals(List.of("alpha")), "IntelliJ XML server persistence");
            check(restored.servers.getFirst().overrides.get("alpha").reasoningEffort.equals("high"), "IntelliJ XML override persistence");
            var refreshSettings = new MollmSettings(); refreshSettings.loadState(restored);
            check(refreshSettings.usageRefreshSeconds() == 180, "Usage refresh interval survives IntelliJ XML persistence");
            restored.usageRefreshSeconds = -1;
            check(refreshSettings.usageRefreshSeconds() == 60, "Invalid stored interval falls back to a safe timer delay");
            System.out.println("PASS: " + checks + " gateway checks; dashboard render check passed.");
            AgentTest.main(new String[0]);
            ImageAttachmentTest.run();
            PresentationTest.run();
            PermissionTest.run();
            SteeringTest.run();
            AttachmentUiTest.run();
            AgentLimitsTest.run();
            ToolReliabilityTest.run();
            SensitiveFilesTest.run();
            ChatLayoutTest.run();
            GitReviewTest.run();
        } finally { server.stop(0); executor.shutdownNow(); }
    }

    private static final class SwingCheck {
        static void run() throws Exception {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                var usage = new UsagePanel();
                var key = JsonParser.parseString("{\"key_alias\":\"Demo gateway\",\"spend\":8.5,\"max_budget\":10,\"rpm_limit\":60,\"tpm_limit\":100000}").getAsJsonObject();
                var days = new com.google.gson.JsonArray();
                for (int i = 29; i >= 0; i--) {
                    String date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(i).toString();
                    days.add(JsonParser.parseString("{\"date\":\"" + date + "\",\"metrics\":{\"spend\":" + (30 - i) * .002
                            + ",\"api_requests\":10,\"failed_requests\":1,\"prompt_tokens\":1000,\"completion_tokens\":100},"
                            + "\"breakdown\":{\"models\":{\"alpha\":{\"metrics\":{\"spend\":0.01,\"api_requests\":10,\"total_tokens\":1100}}}}}"));
                }
                usage.display(new Gateway.Usage(key, days, List.of()));
                usage.setSize(900, 850); usage.doLayout();
                layout(usage);
                var image = new java.awt.image.BufferedImage(900, 850, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                var g = image.createGraphics(); usage.printAll(g); g.dispose();
                try {
                    java.nio.file.Files.createDirectories(java.nio.file.Path.of("build"));
                    javax.imageio.ImageIO.write(image, "png", java.nio.file.Path.of("build/usage-preview.png").toFile());
                } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            });
        }
        private static void layout(java.awt.Container container) {
            container.doLayout();
            for (var child : container.getComponents()) if (child instanceof java.awt.Container nested) layout(nested);
        }
    }
}
