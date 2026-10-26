package tw.com.fmt.mollm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class SensitiveFilesTest {
    private static int checks;
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); checks++; }
    private interface Checked { void run() throws Exception; }
    private static void fails(Checked action, String code) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (IOException e) { check(e.getMessage().contains(code) && !e.getMessage().contains("fixture-password-987"), code + " without secret echo"); }
    }
    private static JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    static void run() throws Exception {
        var state = new MollmSettings.Data(); var policy = SensitiveFiles.from(state);
        check(policy.enabled() && !policy.commandsAllowed(), "Fresh/migrated settings default to strict sensitive-file protection");
        state.sensitiveFilePatterns = new ArrayList<>(List.of("private-?.json", "config/private/**")); state.sensitiveStrictCommands = false;
        var restored = com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(state), MollmSettings.Data.class);
        check(SensitiveFiles.from(restored).commandsAllowed() && SensitiveFiles.from(restored).blocks(Path.of("private-a.json")), "Mode and custom rules persist");
        restored.sensitiveFilePatterns = List.of("x".repeat(201));
        check(SensitiveFiles.from(restored).blocks(Path.of(".env")), "Invalid persisted rules fall back to defaults");
        for (String path : List.of(".env", "child/.env.prod", ".ssh/id_rsa", ".aws/credentials", ".docker/config.json", "application-production.yml", "gradle.properties", "private.pem"))
            check(policy.blocks(Path.of(path)), "Default sensitive path blocked: " + path);
        check(!policy.blocks(Path.of(".agents/skill/SKILL.md")) && !policy.blocks(Path.of("src/Main.java")), "Agent instructions and ordinary source paths remain available");
        var custom = SensitiveFiles.from(state);
        check(custom.blocks(Path.of("config/private/nested/file.txt")) && !custom.blocks(Path.of("config/public/file.txt")), "Nested custom glob scope works");
        for (String text : List.of("db.password=fixture-password-987", "{\"apiKey\":\"fixture-password-987\"}", "<password>fixture-password-987</password>",
                "Authorization: Bearer fixture-password-987", "Authorization: Basic Zml4dHVyZS11c2VyOnBhc3N3b3Jk", "https://fixture-user:fixture-password-987@host/path",
                "ghp_" + "A".repeat(36), "glpat-" + "T".repeat(25), "https://fixture-password-987@host/path",
                "password: \"Example(Only)123!\"", "password: \"ExampleOnly123;\"", "password: \"Example Only 123!\"",
                "sk-" + "B".repeat(30), "-----BEGIN PRIVATE KEY-----\nfixture-password-987\n-----END PRIVATE KEY-----")) {
            fails(() -> policy.checkContent(text), "SENSITIVE_CONTENT_BLOCKED");
            check(!policy.redact(text).equals(text) && !policy.redact(text).contains("fixture-password-987") && !policy.redact(text).contains("Example"), "Sensitive output is redacted");
        }
        policy.checkContent("password=${DB_PASSWORD}\ntoken=null\npublicName=product"); checks++;
        policy.checkContent("password=System.getenv(\"DB_PASS\");"); checks++;
        policy.checkContent("password=process.env.DB_PASS;"); checks++;
        check(SensitiveFiles.disabled().redact("password=fixture-password-987").contains("fixture-password-987"), "Disabled policy preserves explicitly requested legacy behavior");
        Path root = Files.createTempDirectory(Path.of("build"), "sensitive-").toRealPath();
        Files.writeString(root.resolve(".env"), "password=fixture-password-987"); Files.writeString(root.resolve("application.yml"), "server: safe");
        Files.writeString(root.resolve("ordinary.txt"), "password=fixture-password-987\npublic text"); Files.writeString(root.resolve("safe.txt"), "public text");
        Files.writeString(root.resolve("bearer.txt"), "Authorization: Bearer Example(123)");
        Files.writeString(root.resolve("xml.txt"), "<password>Example(123)</password>");
        Files.createDirectories(root.resolve(".agents")); Files.writeString(root.resolve(".agents/SKILL.md"), "review instructions");
        fails(() -> DroppedFile.read(root.resolve(".env"), policy), "SENSITIVE_FILE_BLOCKED");
        fails(() -> DroppedFile.read(root.resolve("ordinary.txt"), policy), "SENSITIVE_CONTENT_BLOCKED");
        fails(() -> DroppedFile.read(root.resolve("bearer.txt"), policy), "SENSITIVE_CONTENT_BLOCKED");
        fails(() -> DroppedFile.read(root.resolve("xml.txt"), policy), "SENSITIVE_CONTENT_BLOCKED");
        check(DroppedFile.read(root.resolve("safe.txt"), policy).text().equals("public text"), "Safe file attachments remain usable");
        var reads = new AtomicInteger(); var approvals = new AtomicInteger();
        var access = new WorkspaceTools.FilesAccess() {
            public String read(Path path) throws Exception { reads.incrementAndGet(); return WorkspaceTools.readUtf8(path); }
            public void apply(WorkspaceTools.Change change, Gateway.Call call) throws Exception { Files.writeString(change.path(), change.after()); }
        };
        var allow = new WorkspaceTools.Approval() {
            public boolean change(WorkspaceTools.Change change, Gateway.Call call) { approvals.incrementAndGet(); return true; }
            public boolean command(WorkspaceTools.Command command, Gateway.Call call) { approvals.incrementAndGet(); return true; }
        };
        var strict = new WorkspaceTools(root, access, allow, policy);
        try (var call = new Gateway.Call()) {
            fails(() -> strict.execute("read_file", args("{\"path\":\".env\"}"), call), "SENSITIVE_FILE_BLOCKED");
            check(reads.get() == 0, "Named sensitive file blocked before disk/editor access");
            fails(() -> strict.execute("read_file", args("{\"path\":\"ordinary.txt\"}"), call), "SENSITIVE_CONTENT_BLOCKED");
            check(strict.execute("read_file", args("{\"path\":\"safe.txt\"}"), call).contains("public text"), "Safe text is readable");
            check(strict.execute("read_file", args("{\"path\":\".agents/SKILL.md\"}"), call).contains("review instructions"), "Hidden instruction files remain readable");
            String list = strict.execute("list_files", args("{}"), call);
            check(!list.contains(".env") && !list.contains("application.yml") && list.contains("safe.txt") && list.contains("scan_complete=false"), "Listings omit named secrets and expose incomplete coverage");
            check(!strict.execute("find_files", args("{\"query\":\"env\"}"), call).contains("[file] .env"), "Name search cannot reveal blocked files");
            String search = strict.execute("search_text", args("{\"query\":\"public\"}"), call);
            check(search.contains("safe.txt") && !search.contains("ordinary.txt:2:") && !search.contains("fixture-password-987"), "Content search skips whole credential-bearing files");
            fails(() -> strict.execute("search_text", args("{\"path\":\".env\",\"query\":\"password\"}"), call), "SENSITIVE_FILE_BLOCKED");
            fails(() -> strict.execute("append_file", args("{\"path\":\"ordinary.txt\",\"expected_chars\":51,\"content\":\"test\"}"), call), "SENSITIVE_CONTENT_BLOCKED");
            fails(() -> strict.execute("run_command", args("{\"executable\":\"nonexistent-command\",\"arguments\":[]}"), call), "COMMANDS_BLOCKED");
            check(approvals.get() == 0, "Permanent or one-time command approval cannot override strict protection");
            Path outside = Files.createTempDirectory(root.getParent(), "outside-sensitive-").toRealPath(); Files.writeString(outside.resolve(".env"), "secret");
            var absolute = new JsonObject(); absolute.addProperty("path", outside.resolve(".env").toString());
            fails(() -> strict.execute("read_file", absolute, call), "SENSITIVE_FILE_BLOCKED");
            try {
                Files.createSymbolicLink(root.resolve("alias.txt"), outside.resolve(".env"));
                fails(() -> strict.execute("read_file", args("{\"path\":\"alias.txt\"}"), call), "SENSITIVE_FILE_BLOCKED");
            } catch (IOException | UnsupportedOperationException e) { System.out.println("SKIP: sensitive symlink check unavailable on this host."); }
            var legacy = new WorkspaceTools(root, access, allow, SensitiveFiles.disabled());
            check(legacy.execute("read_file", args("{\"path\":\".env\"}"), call).contains("fixture-password-987"), "Disabling protection restores file access");
            var relaxed = new WorkspaceTools(root, access, allow, new SensitiveFiles(true, false, SensitiveFiles.DEFAULT_PATTERNS));
            var command = new JsonObject(); command.addProperty("executable", Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java").toString());
            var argv = new com.google.gson.JsonArray(); argv.add("-cp"); argv.add(System.getProperty("java.class.path")); argv.add(Probe.class.getName()); command.add("arguments", argv);
            String output = relaxed.execute("run_command", command, call);
            check(output.contains("Exit code: 0") && output.contains("[REDACTED]") && !output.contains("fixture-password-987") && !output.contains("Zml4dHVyZS"), "Relaxed command output redacts recognizable secrets before model/UI result");
        }
        modelBoundary(strict);
        System.out.println("PASS: " + checks + " sensitive-file checks.");
    }
    public static final class Probe {
        public static void main(String[] args) { System.out.println("password=fixture-password-987\nAuthorization: Basic Zml4dHVyZS11c2VyOnBhc3N3b3Jk\npublic text"); }
    }
    private static void modelBoundary(WorkspaceTools workspace) throws Exception {
        var requests = new AtomicInteger(); var error = new AtomicReference<Throwable>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); var request = args(input);
                check(!input.contains("fixture-password-987"), "Credential never enters HTTP model request");
                check(request.getAsJsonArray("tools").asList().stream().noneMatch(value -> value.getAsJsonObject().getAsJsonObject("function").get("name").getAsString().equals("run_command")), "Strict policy removes command from model's tool definitions");
                int count = requests.incrementAndGet(); String delta = count == 1 ? "{\"tool_calls\":[{\"index\":0,\"id\":\"read-secret\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"ordinary.txt\\\"}\"}}]}" : "{\"content\":\"done\"}";
                byte[] bytes = ("data: {\"choices\":[{\"delta\":" + delta + "}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable e) { error.set(e); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        }); server.start();
        try (var call = new Gateway.Call()) {
            var activity = new StringBuilder(); var api = new Gateway("http://127.0.0.1:" + server.getAddress().getPort(), "", 2000);
            var result = new AgentLoop(workspace, List.of(new Gateway.Message("user", "read file"))).run(api, new Gateway.Model("mock", new JsonObject()), new MollmSettings.Server(), call,
                    (kind, text) -> {}, activity::append);
            check(result.completed() && requests.get() == 2 && !activity.toString().contains("fixture-password-987"), "Blocked contents also stay out of activity transcript");
            if (error.get() != null) throw new AssertionError(error.get());
        } finally { server.stop(0); }
    }
}
