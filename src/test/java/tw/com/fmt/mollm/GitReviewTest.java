package tw.com.fmt.mollm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

final class GitReviewTest {
    private static int checks;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private interface Checked { void run() throws Exception; }
    private static void fails(Checked operation, String code) throws Exception {
        try { operation.run(); throw new AssertionError("Expected " + code); }
        catch (IOException e) { check(e.getMessage().contains(code), code); }
    }
    private static JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    static void run() throws Exception {
        Path root = Files.createTempDirectory(Path.of("build"), "git-review-").toRealPath();
        setup(root, "init", "--quiet"); setup(root, "config", "user.name", "Fixture"); setup(root, "config", "user.email", "fixture@example.invalid");
        Files.writeString(root.resolve("Main.java"), "class Main { String value = \"before\"; }\n");
        Files.writeString(root.resolve("application.yml"), "password: fixture-sensitive-value\n");
        Files.writeString(root.resolve("ordinary.txt"), "token=fixture-sensitive-value\n");
        setup(root, "add", "."); setup(root, "commit", "--quiet", "-m", "before password=fixture-sensitive-value"); String base = setup(root, "rev-parse", "HEAD").trim();
        Files.writeString(root.resolve("Main.java"), "class Main { String value = \"after\"; }\n");
        Files.writeString(root.resolve("application.yml"), "password: fixture-other-value\n");
        Files.writeString(root.resolve("ordinary.txt"), "token=fixture-other-value\n");
        setup(root, "add", "."); setup(root, "commit", "--quiet", "-m", "after"); String head = setup(root, "rev-parse", "HEAD").trim();
        setup(root, "update-ref", "refs/merge-requests/4/head", head); setup(root, "update-ref", "refs/merge-requests/4/merge", head);
        setup(root, "gc", "--prune=now"); setup(root, "update-server-info");
        try (var packs = Files.list(root.resolve(".git/objects/pack"))) { check(packs.anyMatch(path -> path.toString().endsWith(".pack")), "Fixture commits are packed like fetched Git objects"); }
        Files.writeString(root.resolve("Main.java"), "user unsaved-on-disk work\n");
        String originalHead = Files.readString(root.resolve(".git/HEAD")); byte[] originalIndex = Files.readAllBytes(root.resolve(".git/index"));
        var approvals = new AtomicInteger();
        var approved = new WorkspaceTools.Approval() {
            public boolean change(WorkspaceTools.Change change, Gateway.Call call) { return false; }
            public boolean command(WorkspaceTools.Command command, Gateway.Call call) { throw new AssertionError("General command approval was used"); }
            public boolean gitFetch(WorkspaceTools.Command command, Gateway.Call call) {
                approvals.incrementAndGet(); check(command.argv().contains("--no-write-fetch-head") && command.argv().stream().noneMatch(value -> value.equals("checkout")), "Network approval previews only restricted fetch"); return true;
            }
        };
        var policy = SensitiveFiles.from(new MollmSettings.Data());
        var git = new GitReview(root, policy, approved);
        setup(root, "config", "core.fsmonitor", "forbidden-fsmonitor-helper");
        setup(root, "config", "diff.external", "forbidden-diff-helper");
        setup(root, "config", "diff.mollm.textconv", "forbidden-textconv-helper");
        Files.writeString(root.resolve(".gitattributes"), "Main.java diff=mollm\n");
        try (var call = new Gateway.Call()) {
            check(git.execute("git_read", args("{\"operation\":\"status\"}"), call).contains("Main.java"), "Strict mode reads local status without run_command");
            var show = args("{\"operation\":\"show\",\"path\":\"Main.java\"}"); show.addProperty("revision", base);
            String old = git.execute("git_read", show, call);
            check(old.contains("before") && !old.contains("user unsaved-on-disk"), "Show reads packed historical commit without checkout");
            check(old.contains("1: class Main"), "Historical source numbers start at actual source line one");
            show.addProperty("revision", head);
            check(git.execute("git_read", show, call).contains("after"), "Latest packed commit is readable");
            var diff = args("{\"operation\":\"diff\",\"path\":\"Main.java\"}"); diff.addProperty("base", base); diff.addProperty("revision", head);
            String patch = git.execute("git_read", diff, call);
            check(patch.contains("-class Main") && patch.contains("+class Main"), "Diff returns actual changed content");
            check(!patch.contains("forbidden-diff-helper") && !old.contains("forbidden-textconv-helper"), "External diff/textconv helpers are disabled");
            var files = args("{\"operation\":\"files\"}"); files.addProperty("base", base); files.addProperty("revision", head);
            String names = git.execute("git_read", files, call);
            check(names.contains("Main.java") && !names.contains("application.yml"), "Changed-file listing excludes protected paths");
            show.addProperty("path", "application.yml"); fails(() -> git.execute("git_read", show, call), "SENSITIVE_FILE_BLOCKED");
            show.addProperty("path", "ordinary.txt"); fails(() -> git.execute("git_read", show, call), "SENSITIVE_CONTENT_BLOCKED");
            diff.addProperty("path", "ordinary.txt"); fails(() -> git.execute("git_read", diff, call), "SENSITIVE_CONTENT_BLOCKED");
            check(git.execute("git_read", args("{\"operation\":\"refs\"}"), call).contains(head), "Refs expose fetched revisions");
            check(git.execute("git_read", args("{\"operation\":\"log\",\"page_size\":1}"), call).contains("next_offset=1"), "Git text results are paginated");
            String log = git.execute("git_read", args("{\"operation\":\"log\"}"), call);
            check(!log.contains("fixture-sensitive-value") && log.contains("[REDACTED]"), "Commit message credentials are redacted");
            for (String bad : List.of("--exec=calc", "HEAD;calc", "HEAD:path", "$(calc)", "a..b")) fails(() -> GitReview.revision(bad), "INVALID_REVISION");
            fails(() -> git.execute("git_read", args("{\"operation\":\"checkout\"}"), call), "INVALID_GIT_OPERATION");
            fails(() -> git.execute("git_read", args("{\"operation\":\"show\",\"path\":\"../secret\"}"), call), "INVALID_GIT_PATH");
            fails(() -> git.execute("git_read", args("{\"operation\":\"show\",\"path\":\":(attr)file\"}"), call), "INVALID_GIT_PATH");
            fails(() -> git.execute("git_read", args("{\"operation\":\"status\",\"repository\":\"..\"}"), call), "INVALID_REPOSITORY");
            setup(root, "config", "remote.origin.url", "file:///not-allowed");
            fails(() -> git.execute("git_fetch_mr", args("{\"mr_number\":4}"), call), "UNSUPPORTED_REMOTE");
            check(approvals.get() == 0, "Forbidden transport rejected before approval or network access");
        }
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Path database = root.resolve(".git");
        http.createContext("/repo/", exchange -> {
            Path file = database.resolve(exchange.getRequestURI().getPath().substring("/repo/".length())).normalize();
            if (!file.startsWith(database) || !Files.isRegularFile(file)) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            byte[] bytes = Files.readAllBytes(file); exchange.getResponseHeaders().set("Content-Type", file.endsWith("refs") ? "text/plain" : "application/octet-stream");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); http.start();
        setup(root, "config", "remote.origin.url", "http://127.0.0.1:" + http.getAddress().getPort() + "/repo");
        try (var call = new Gateway.Call()) {
            var denied = new GitReview(root, policy, new WorkspaceTools.Approval() {
                public boolean change(WorkspaceTools.Change change, Gateway.Call token) { return false; }
                public boolean command(WorkspaceTools.Command command, Gateway.Call token) { throw new AssertionError("General approval bypass"); }
                public boolean gitFetch(WorkspaceTools.Command command, Gateway.Call token) { return false; }
            });
            check(denied.execute("git_fetch_mr", args("{\"mr_number\":4}"), call).startsWith("Denied:"), "MR fetch needs one-time approval");
            check(denied.execute("git_fetch_mr", args("{\"mr_number\":4}"), call).startsWith("Denied previously:"), "Denied fetch is not repeatedly requested");
            var changing = new GitReview(root, policy, new WorkspaceTools.Approval() {
                public boolean change(WorkspaceTools.Change change, Gateway.Call token) { return false; }
                public boolean command(WorkspaceTools.Command command, Gateway.Call token) { return false; }
                public boolean gitFetch(WorkspaceTools.Command command, Gateway.Call token) throws Exception { setup(root, "config", "remote.origin.url", "https://changed.invalid/repo"); return true; }
            });
            fails(() -> changing.execute("git_fetch_mr", args("{\"mr_number\":4}"), call), "GIT_REMOTE_CHANGED");
            setup(root, "config", "remote.origin.url", "http://127.0.0.1:" + http.getAddress().getPort() + "/repo");
            String fetched = git.execute("git_fetch_mr", args("{\"mr_number\":4}"), call);
            check(fetched.contains(head) && approvals.get() == 1, "Approved MR fetch works over real loopback HTTP with packed objects");
            check(setup(root, "rev-parse", "refs/mollm/merge-requests/4/head").trim().equals(head), "Fetch updates only dedicated MR head ref");
            check(fetched.contains("目前無法取得或版本不一致"), "MR merge fallback gracefully if it is invalid or not actually merged from head");

            // Build a valid two-parent merge commit without checkout or touching the
            // intentionally dirty worktree/index. A regular merge could fast-forward.
            String mergeTree = setup(root, "rev-parse", head + "^{tree}").trim();
            String validMerge = setup(root, "commit-tree", mergeTree, "-p", base, "-p", head, "-m", "merge commit").trim();
            setup(root, "update-ref", "refs/merge-requests/5/head", head); setup(root, "update-ref", "refs/merge-requests/5/merge", validMerge);
            // Dumb HTTP fixture serves info/refs; publish newly-created refs and objects.
            setup(root, "update-server-info");
            String fetchedValid = git.execute("git_fetch_mr", args("{\"mr_number\":5}"), call);
            check(fetchedValid.contains("merge 參照已更新"), "MR merge ref is accepted when it actually has head as a parent");

            check(Files.readString(root.resolve(".git/HEAD")).equals(originalHead) && java.util.Arrays.equals(Files.readAllBytes(root.resolve(".git/index")), originalIndex)
                    && Files.readString(root.resolve("Main.java")).equals("user unsaved-on-disk work\n"), "Git reading and fetch preserve branch, index and dirty working file");
            check(!Files.exists(root.resolve(".git/FETCH_HEAD")), "Fetch does not overwrite FETCH_HEAD");
        } finally { http.stop(0); }
        try (var call = new Gateway.Call()) {
            call.cancel(); boolean cancelled = false;
            try { git.execute("git_read", args("{\"operation\":\"status\"}"), call); }
            catch (java.util.concurrent.CancellationException expected) { cancelled = true; }
            check(cancelled, "Cancelled Git operation does not start a process");
        }
        System.out.println("PASS: " + checks + " restricted Git review checks.");
    }
    private static String setup(Path repo, String... arguments) throws Exception {
        var command = new ArrayList<String>(); command.add(GitReview.executable().toString()); command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1"); builder.environment().put("GIT_CONFIG_GLOBAL", "NUL");
        var process = builder.start(); process.getOutputStream().close(); byte[] bytes = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) throw new AssertionError("Fixture Git failed: " + new String(bytes, StandardCharsets.UTF_8));
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
