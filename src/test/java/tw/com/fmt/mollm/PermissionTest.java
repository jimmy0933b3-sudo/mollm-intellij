package tw.com.fmt.mollm;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

final class PermissionTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name); checks++;
    }
    static void run() throws Exception {
        Path root = Files.createTempDirectory(Path.of("build"), "permission-test-").toRealPath();
        Path sub = Files.createDirectory(root.resolve("sub"));
        Path outside = Files.createDirectory(root.resolveSibling(root.getFileName() + "-outside"));
        var settings = new MollmSettings(); var permissions = new DirectoryPermissions(settings);
        check(!permissions.allows(root, root, DirectoryPermissions.Kind.FILES), "New installations require approval");
        var reviews = new AtomicInteger();
        WorkspaceTools.Approval approval = new WorkspaceTools.Approval() {
            public boolean change(WorkspaceTools.Change change, Gateway.Call token) {
                if (permissions.allows(root, change.path(), DirectoryPermissions.Kind.FILES)) return true;
                reviews.incrementAndGet(); return true; // User chooses Allow once.
            }
            public boolean command(WorkspaceTools.Command command, Gateway.Call token) {
                if (permissions.allows(root, command.directory(), DirectoryPermissions.Kind.COMMANDS)) return true;
                reviews.incrementAndGet(); return false;
            }
        };
        var files = new WorkspaceTools.FilesAccess() {
            public String read(Path path) throws Exception { return Files.readString(path); }
            public void apply(WorkspaceTools.Change change, Gateway.Call token) throws Exception { Files.writeString(change.path(), change.after()); }
        };
        try (var call = new Gateway.Call()) {
            var tools = new WorkspaceTools(root, files, approval);
            tools.execute("create_file", create("first.txt"), call);
            tools.execute("create_file", create("second.txt"), call);
            check(reviews.get() == 2 && permissions.grants().isEmpty(), "Allow once does not suppress subsequent reviews or persist grants");
            permissions.allow(root, DirectoryPermissions.Kind.FILES);
            tools.execute("create_file", create("sub/third.txt"), call);
            check(reviews.get() == 2 && Files.exists(sub.resolve("third.txt")), "Permanent file approval skips reviews inside working directory");
        }
        check(permissions.allows(root, sub.resolve("new/nested/file.txt"), DirectoryPermissions.Kind.FILES), "Grant covers new files and nested paths");
        check(!permissions.allows(root, outside.resolve("file.txt"), DirectoryPermissions.Kind.FILES), "Directory name prefix cannot escape scope");
        check(!permissions.allows(root, root.resolve("../outside.txt"), DirectoryPermissions.Kind.FILES), "Parent traversal cannot use grant");
        check(!permissions.allows(sub, sub.resolve("file.txt"), DirectoryPermissions.Kind.FILES), "Changing Agent root requires its own grant");
        check(!permissions.allows(root, sub, DirectoryPermissions.Kind.COMMANDS), "File grant cannot approve commands");
        permissions.allow(root, DirectoryPermissions.Kind.COMMANDS);
        check(permissions.grants().size() == 1 && permissions.allows(root, sub, DirectoryPermissions.Kind.COMMANDS), "Commands are independently granted for same root");
        check(!permissions.allows(root, outside, DirectoryPermissions.Kind.COMMANDS), "Command directory must remain in granted root");
        var restored = com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(settings.getState()), MollmSettings.Data.class);
        var reloaded = new MollmSettings(); reloaded.loadState(restored); var saved = new DirectoryPermissions(reloaded);
        check(saved.allows(root, root, DirectoryPermissions.Kind.FILES) && saved.allows(root, sub, DirectoryPermissions.Kind.COMMANDS), "Both permission kinds survive IDE restart serialization");
        saved.revoke(root.toString());
        check(!saved.allows(root, root, DirectoryPermissions.Kind.FILES) && !saved.allows(root, root, DirectoryPermissions.Kind.COMMANDS), "Revocation restores approval for both kinds");
        permissions.set(root, false, true);
        check(!permissions.allows(root, root, DirectoryPermissions.Kind.FILES) && permissions.allows(root, root, DirectoryPermissions.Kind.COMMANDS), "Settings can revoke files while keeping commands");
        permissions.set(root, false, false); check(permissions.grants().isEmpty(), "Unchecking both removes permanent permission");
        permissions.allow(root, DirectoryPermissions.Kind.FILES);
        var mcp = new MollmSettings.McpServerConfig();
        mcp.id = "permission-test-mcp";
        mcp.name = "Test MCP";
        mcp.transport = "stdio";
        mcp.executable = "npx";
        mcp.args = java.util.List.of("server-tool");
        check(!permissions.allowsMcp(root, mcp, "read_resource"), "MCP tools require initial approval");
        permissions.allowMcp(root, mcp, "read_resource");
        check(permissions.allowsMcp(root, mcp, "read_resource"), "Always allow authorizes one MCP tool");
        check(!permissions.allowsMcp(root, mcp, "delete_resource"), "Other MCP tools do not inherit grant");
        check(!permissions.allowsMcp(sub, mcp, "read_resource"), "MCP grant is scoped to selected Agent workspace");
        var anotherMcp = new MollmSettings.McpServerConfig();
        anotherMcp.id = "another-mcp"; anotherMcp.transport = mcp.transport;
        anotherMcp.executable = mcp.executable; anotherMcp.args = mcp.args;
        check(!permissions.allowsMcp(root, anotherMcp, "read_resource"), "MCP server IDs cannot share grants");
        mcp.baseUrl = "http://different-server";
        check(!permissions.allowsMcp(root, mcp, "read_resource"), "Connection change invalidates saved grant");
        mcp.baseUrl = "";
        var persisted = com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(settings.getState()), MollmSettings.Data.class);
        var reloadedMcp = new DirectoryPermissions(new MollmSettings());
        var reloadedSettings = new MollmSettings(); reloadedSettings.loadState(persisted);
        reloadedMcp = new DirectoryPermissions(reloadedSettings);
        check(reloadedMcp.allowsMcp(root, mcp, "read_resource"), "MCP grants survive settings serialization");
        reloadedMcp.revokeMcp(reloadedMcp.mcpGrants().getFirst());
        check(!reloadedMcp.allowsMcp(root, mcp, "read_resource"), "MCP grants can be revoked");
        mcp.enabled = false;
        check(!permissions.allowsMcp(root, mcp, "read_resource"), "Disabled MCP server cannot auto approve");
        permissions.revokeMcp(permissions.mcpGrants().getFirst());
        try (var call = new Gateway.Call()) {
            call.cancel();
            try { new WorkspaceTools(root, files, approval).execute("create_file", create("cancelled.txt"), call); throw new AssertionError("Cancelled write accepted"); }
            catch (java.util.concurrent.CancellationException expected) { check(!Files.exists(root.resolve("cancelled.txt")), "Permanent grant does not override Stop"); }
        }
        var invalid = new MollmSettings.DirectoryGrant(); invalid.directory = "invalid\u0000path"; invalid.allowFiles = true;
        settings.getState().directoryGrants = java.util.List.of(invalid);
        check(!permissions.allows(root, root, DirectoryPermissions.Kind.FILES), "Malformed stored grant never auto-approves");
        Path missing = Files.createDirectory(root.resolve("removed")); permissions.allow(missing, DirectoryPermissions.Kind.FILES); Files.delete(missing);
        check(!permissions.allows(missing, missing, DirectoryPermissions.Kind.FILES), "Removed directories cannot use stale grant");
        permissions.allow(root, DirectoryPermissions.Kind.FILES);
        try {
            var link = Files.createSymbolicLink(root.resolve("link"), outside);
            check(!permissions.allows(root, link.resolve("file.txt"), DirectoryPermissions.Kind.FILES), "Symlink target outside root cannot use grant");
        } catch (java.io.IOException | UnsupportedOperationException e) { System.out.println("SKIP: symlink permission check unavailable on this host."); }
        System.out.println("PASS: " + checks + " permission checks (one-time decisions, directory boundaries, persistence and revocation).");
    }
    private static JsonObject create(String path) {
        var args = new JsonObject(); args.addProperty("path", path); args.addProperty("content", "test"); return args;
    }
}
