package tw.com.fmt.mollm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentTest {
    private static int checks;
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description); checks++;
    }
    private static JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private static WorkspaceTools.FilesAccess diskFiles() {
        return new WorkspaceTools.FilesAccess() {
            public String read(Path path) throws Exception { return WorkspaceTools.readUtf8(path); }
            public void apply(WorkspaceTools.Change change, Gateway.Call token) throws Exception {
                AgentLoop.checkCancelled(token); Files.createDirectories(change.path().getParent());
                Files.writeString(change.path(), change.after(), StandardCharsets.UTF_8);
            }
        };
    }
    private static WorkspaceTools.Approval approve(boolean allowed) {
        return new WorkspaceTools.Approval() {
            public boolean change(WorkspaceTools.Change change, Gateway.Call token) { return allowed; }
            public boolean command(WorkspaceTools.Command command, Gateway.Call token) { return allowed; }
        };
    }
    private interface Checked { void run() throws Exception; }
    private static void fails(Checked operation, String label) throws Exception {
        try { operation.run(); throw new AssertionError(label); }
        catch (IOException expected) { checks++; }
    }

    public static void main(String[] unused) throws Exception {
        Files.createDirectories(Path.of("build"));
        Path root = Files.createTempDirectory(Path.of("build"), "agent-test-").toAbsolutePath().toRealPath();
        Files.writeString(root.resolve("Main.java"), "class Main {\n  String value = \"old\";\n}\n");
        Files.writeString(root.resolve("repeat.txt"), "repeat repeat");
        Files.writeString(root.resolve("AGENTS.md"), "Use local tests.");
        Files.writeString(root.resolve(".env"), "PRIVATE=do-not-read");
        Files.createDirectories(root.resolve(".git")); Files.writeString(root.resolve(".git/config"), "PRIVATE=do-not-read");
        for (String directory : List.of(".agent", ".agents", ".claude", "node_modules", "build")) {
            Files.createDirectories(root.resolve(directory)); Files.writeString(root.resolve(directory).resolve("rules.md"), "read-access-fixture " + directory);
        }
        Files.writeString(root.resolve("private.key"), "read-access-fixture key");
        Path outside = Files.createTempDirectory(root.getParent(), "outside-read-test-").toRealPath();
        Files.writeString(outside.resolve("external.md"), "read-access-fixture external");
        Files.write(root.resolve("binary.bin"), new byte[]{0, 1, 2});
        var allow = new WorkspaceTools(root, diskFiles(), approve(true));
        var deny = new WorkspaceTools(root, diskFiles(), approve(false));
        try (var token = new Gateway.Call()) {
            for (String path : List.of("../outside.txt", "C:\\Windows\\test", ".env", ".git/config", "private.key", "credentials.json", "node_modules/test.js"))
                fails(() -> allow.resolve(path), "Forbidden path accepted: " + path);
            check(WorkspaceTools.selectRoot(root, ".").equals(root), "Workspace scope");
            fails(() -> WorkspaceTools.selectRoot(root, ".."), "Scope escapes project");
            check(allow.execute("list_files", args("{}"), token).contains("AGENTS.md"), "Repository instruction discovery");
            check(allow.execute("list_files", args("{}"), token).contains(".env"), "Hidden files included in listing");
            check(allow.execute("read_file", args("{\"path\":\".git/config\"}"), token).contains("PRIVATE="), "Previously excluded hidden directory is readable");
            check(allow.execute("read_file", args("{\"path\":\".env\"}"), token).contains("PRIVATE="), "Dot file is readable");
            check(allow.execute("read_file", args("{\"path\":\"private.key\"}"), token).contains("read-access-fixture"), "No extension-based read exclusions");
            var hiddenMatches = allow.execute("search_text", args("{\"query\":\"read-access-fixture\"}"), token);
            for (String directory : List.of(".agent", ".agents", ".claude", "node_modules", "build"))
                check(hiddenMatches.contains(directory + "/rules.md"), "Search includes " + directory);
            var outsideArgs = new JsonObject(); outsideArgs.addProperty("path", outside.resolve("external.md").toString());
            check(deny.execute("read_file", outsideArgs, token).contains("read-access-fixture external"), "Absolute outside read does not require write approval");
            outsideArgs.addProperty("path", "../" + outside.getFileName() + "/external.md");
            check(allow.execute("read_file", outsideArgs, token).contains("external"), "Parent-relative outside read works");
            outsideArgs.addProperty("path", outside.toString());
            check(allow.execute("list_files", outsideArgs, token).contains(outside.resolve("external.md").toString().replace('\\', '/')), "Outside listing returns absolute usable paths");
            outsideArgs.addProperty("query", "external");
            check(allow.execute("search_text", outsideArgs, token).contains("external.md:1:"), "Outside directory search works");
            outsideArgs.addProperty("path", outside.resolve("external.md").toString());
            check(allow.execute("search_text", outsideArgs, token).contains("external.md:1:"), "Outside single-file search works");
            outsideArgs.addProperty("old_text", "external"); outsideArgs.addProperty("new_text", "edited");
            fails(() -> allow.execute("replace_in_file", outsideArgs, token), "Outside edit accepted after enabling outside reads");
            fails(() -> allow.execute("create_file", args("{\"path\":\".agents/new.md\",\"content\":\"edit\"}"), token), "Hidden write restrictions were removed with reads");
            check(allow.execute("read_file", args("{\"path\":\"Main.java\",\"start_line\":2,\"end_line\":2}"), token).contains("2:   String"), "Numbered file slices");
            check(allow.execute("search_text", args("{\"query\":\"old\"}"), token).contains("Main.java:2:"), "Literal source search");
            check(allow.execute("search_text", args("{\"query\":\"old\",\"path\":\"Main.java\"}"), token).contains("Main.java:2:"), "Search targets a single source file without scanning unrelated paths");
            fails(() -> allow.execute("read_file", args("{\"path\":\"binary.bin\"}"), token), "Binary read accepted");
            var edit = args("{\"path\":\"Main.java\",\"old_text\":\"old\",\"new_text\":\"new\"}");
            check(deny.execute("replace_in_file", edit, token).startsWith("Denied"), "Edit approval denied");
            check(deny.execute("replace_in_file", edit, token).startsWith("Denied previously"), "Repeated denied edit does not ask again");
            check(Files.readString(root.resolve("Main.java")).contains("old"), "Denied edit leaves disk untouched");
            check(allow.execute("replace_in_file", edit, token).startsWith("Applied"), "Approved edit applies");
            check(Files.readString(root.resolve("Main.java")).contains("new"), "Approved edit persisted");
            fails(() -> allow.execute("replace_in_file", args("{\"path\":\"repeat.txt\",\"old_text\":\"repeat\",\"new_text\":\"x\"}"), token), "Ambiguous replacement accepted");
            check(allow.execute("create_file", args("{\"path\":\"src/New.java\",\"content\":\"class New {}\"}"), token).contains("new file"), "Approved new file");
            fails(() -> allow.execute("create_file", args("{\"path\":\"src/New.java\",\"content\":\"overwrite\"}"), token), "Existing file overwritten");
            var concurrent = new WorkspaceTools(root, diskFiles(), new WorkspaceTools.Approval() {
                public boolean change(WorkspaceTools.Change change, Gateway.Call call) throws Exception {
                    Files.writeString(change.path(), "changed by user"); return true;
                }
                public boolean command(WorkspaceTools.Command command, Gateway.Call call) { return false; }
            });
            fails(() -> concurrent.execute("replace_in_file", args("{\"path\":\"repeat.txt\",\"old_text\":\"repeat repeat\",\"new_text\":\"agent\"}"), token), "Concurrent edit overwritten");
            check(Files.readString(root.resolve("repeat.txt")).equals("changed by user"), "User changes preserved during approval");
            check(deny.execute("run_command", args("{\"executable\":\"nonexistent-command\",\"arguments\":[]}"), token).startsWith("Denied"), "Denied command never launches");
            check(deny.execute("run_command", args("{\"arguments\":[],\"executable\":\"nonexistent-command\",\"timeout_seconds\":120}"), token).startsWith("Denied previously"), "Repeated denied command blocked even with reordered arguments or different timeout");
            JsonObject command = probeCommand("report");
            String result = allow.execute("run_command", command, token);
            check(result.contains("Exit code: 0") && result.contains(root.toString()), "Approved command uses selected cwd and captures output");
            check(allow.execute("run_command", probeCommand("fail"), token).contains("Exit code: 7"), "Command failure propagated");
            var timeout = probeCommand("sleep"); timeout.addProperty("timeout_seconds", 1);
            check(allow.execute("run_command", timeout, token).contains("Timed out"), "Command timeout");
            var excessive = args("{\"executable\":\"java\",\"arguments\":[],\"timeout_seconds\":121}");
            fails(() -> allow.execute("run_command", excessive, token), "Excessive command timeout accepted");
        }
        try (var token = new Gateway.Call()) {
            var cancelAtApproval = new WorkspaceTools(root, diskFiles(), new WorkspaceTools.Approval() {
                public boolean change(WorkspaceTools.Change change, Gateway.Call call) { call.cancel(); return true; }
                public boolean command(WorkspaceTools.Command command, Gateway.Call call) { call.cancel(); return true; }
            });
            try {
                cancelAtApproval.execute("create_file", args("{\"path\":\"cancelled.txt\",\"content\":\"never\"}"), token);
                throw new AssertionError("Cancelled edit applied");
            } catch (CancellationException expected) { check(!Files.exists(root.resolve("cancelled.txt")), "Cancel during approval prevents write"); }
        }
        var executor = Executors.newCachedThreadPool();
        try (var token = new Gateway.Call()) {
            var job = executor.submit(() -> {
                try { allow.execute("run_command", probeCommand("sleep"), token); return false; }
                catch (CancellationException e) { return true; }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            Thread.sleep(300); token.cancel();
            check(job.get(3, TimeUnit.SECONDS), "Cancel running process promptly");
        } finally { executor.shutdownNow(); }
        toolLoop(root);
        boundedAndCancelledLoops();
        System.out.println("PASS: " + checks + " agent checks (workspace, approvals, commands and streamed tool loop).");
    }

    private static JsonObject probeCommand(String mode) {
        var command = new JsonObject();
        command.addProperty("executable", Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java").toString());
        var argv = new JsonArray(); argv.add("-cp"); argv.add(System.getProperty("java.class.path"));
        argv.add(Probe.class.getName()); argv.add(mode); command.add("arguments", argv); return command;
    }
    public static final class Probe {
        public static void main(String[] arguments) throws Exception {
            if (arguments[0].equals("sleep")) Thread.sleep(30_000);
            else if (arguments[0].equals("fail")) System.exit(7);
            else System.out.println(Path.of(".").toRealPath());
        }
    }

    private static String fragment(int index, String id, String name, String arguments) {
        var call = new JsonObject(); call.addProperty("index", index);
        if (!id.isEmpty()) call.addProperty("id", id);
        var function = new JsonObject(); function.addProperty("name", name); function.addProperty("arguments", arguments); call.add("function", function);
        var calls = new JsonArray(); calls.add(call); var delta = new JsonObject(); delta.add("tool_calls", calls);
        var choice = new JsonObject(); choice.add("delta", delta); var choices = new JsonArray(); choices.add(choice);
        var chunk = new JsonObject(); chunk.add("choices", choices); return "data: " + chunk + "\n\n";
    }
    private static void toolLoop(Path root) throws Exception {
        Files.writeString(root.resolve("loop.txt"), "before");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var round = new AtomicInteger(); var error = new AtomicReference<Throwable>();
        server.createContext("/v1/chat/completions", exchange -> {
            String events;
            try {
                var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                check(request.getAsJsonArray("tools").size() == 13, "All agent tools declared");
                JsonArray messages = request.getAsJsonArray("messages");
                check(messages.get(0).getAsJsonObject().get("role").getAsString().equals("system"), "Agent system context");
                int step = round.getAndIncrement();
                events = switch (step) {
                    case 0 -> "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"Read before editing.\"}}]}\n\n" + fragment(1, "search", "search_text", "{\"query\":\"before\"}")
                            + fragment(0, "read", "read_", "{\"path\":") + fragment(0, "", "file", "\"loop.txt\"}");
                    case 1 -> {
                        check(messages.get(messages.size() - 3).getAsJsonObject().get("reasoning_content").getAsString().equals("Read before editing."), "Tool assistant retains reasoning for thinking models");
                        check(messages.get(messages.size() - 2).getAsJsonObject().get("tool_call_id").getAsString().equals("read"), "Interleaved tool indexes reassembled");
                        check(messages.get(messages.size() - 2).getAsJsonObject().get("content").getAsString().contains("before"), "Read result returned to model");
                        check(messages.get(messages.size() - 1).getAsJsonObject().get("tool_call_id").getAsString().equals("search"), "Tool ids paired with results");
                        yield fragment(0, "edit", "replace_in_file", "{\"path\":\"loop.txt\",\"old_text\":\"before\",\"new_text\":\"after\"}");
                    }
                    case 2 -> {
                        check(messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString().startsWith("Applied"), "Edit result returned to model");
                        yield fragment(0, "test", "run_command", probeCommand("report").toString());
                    }
                    default -> {
                        check(messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString().contains("Exit code: 0"), "Command output returned to model");
                        yield "data: {\"choices\":[{\"delta\":{\"content\":\"Edits and tests complete.\"}}]}\n\n";
                    }
                };
            } catch (Throwable failure) { error.set(failure); events = "data: {\"error\":\"test assertion\"}\n\n"; }
            events += "data: [DONE]\n\n";
            byte[] bytes = events.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try (var token = new Gateway.Call()) {
            var api = new Gateway("http://127.0.0.1:" + server.getAddress().getPort(), "", 2000);
            var engine = new AgentLoop(new WorkspaceTools(root, diskFiles(), approve(true)), List.of(new Gateway.Message("user", "Edit and test")));
            var result = engine.run(api, new Gateway.Model("mock", new JsonObject()), new MollmSettings.Server(), token, (kind, text) -> { }, activity -> { });
            if (error.get() != null) throw new AssertionError("Mock gateway assertion", error.get());
            check(round.get() == 4 && result.completed(), "Agent runs four model rounds to final answer");
            check(Files.readString(root.resolve("loop.txt")).equals("after"), "Full loop edits actual test workspace");
            check(result.messages().stream().filter(m -> m.role().equals("tool")).count() == 4, "Complete persistent tool history");
        } finally { server.stop(0); }
    }

    private static void boundedAndCancelledLoops() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var callsPerRound = new AtomicInteger(1); var requests = new AtomicInteger(); var executed = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes(); int request = requests.incrementAndGet();
            var events = new StringBuilder();
            for (int i = 0; i < callsPerRound.get(); i++) events.append(fragment(i, "tool_" + request + "_" + i, "list_files", "{}"));
            events.append("data: [DONE]\n\n"); byte[] bytes = events.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var cancelAfterTool = new java.util.concurrent.atomic.AtomicBoolean();
        AgentLoop.Tools tools = new AgentLoop.Tools() {
            public String context() { return "test workspace"; }
            public String execute(String name, JsonObject args, Gateway.Call cancellation) {
                executed.incrementAndGet(); if (cancelAfterTool.get()) cancellation.cancel(); return "Applied operation.";
            }
        };
        try {
            var api = new Gateway("http://127.0.0.1:" + server.getAddress().getPort(), "", 1000);
            for (int count : List.of(1, 4)) {
                callsPerRound.set(count); requests.set(0); executed.set(0);
                var engine = new AgentLoop(tools, List.of(new Gateway.Message("user", "Bound the loop")), new AgentInbox(), new AgentLimits(20, 64));
                try (var token = new Gateway.Call()) {
                    var result = engine.run(api, new Gateway.Model("mock", new JsonObject()), new MollmSettings.Server(), token, (kind, text) -> { }, activity -> { });
                    check(!result.completed(), "Limit is reported as incomplete");
                    check(count == 1 ? requests.get() == 20 : executed.get() == 64, "Model round and operation limits");
                }
            }
            callsPerRound.set(2); executed.set(0); cancelAfterTool.set(true);
            var engine = new AgentLoop(tools, List.of(new Gateway.Message("user", "Cancel after first operation")));
            try (var token = new Gateway.Call()) {
                try {
                    engine.run(api, new Gateway.Model("mock", new JsonObject()), new MollmSettings.Server(), token, (kind, text) -> { }, activity -> { });
                    throw new AssertionError("Cancelled loop continued");
                } catch (CancellationException expected) {
                    check(executed.get() == 1, "Cancellation stops remaining tools in batch");
                    check(engine.snapshot().get(2).content().equals("Applied operation."), "Interrupted loop retains completed operation");
                    check(engine.snapshot().get(3).content().startsWith("Cancelled"), "Interrupted loop pairs pending tool id with cancelled result");
                }
            }
        } finally { server.stop(0); }
    }
}
