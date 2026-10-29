package tw.com.fmt.mollm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/** Persistent opt-in permissions are isolated by canonical Agent working directory and tool kind. */
final class DirectoryPermissions {
    enum Kind { FILES, COMMANDS }
    private final MollmSettings settings;
    DirectoryPermissions(MollmSettings settings) { this.settings = settings; }

    boolean allows(Path root, Path target, Kind kind) {
        try {
            Path canonicalRoot = stableRoot(root);
            if (!canonicalTarget(target).startsWith(canonicalRoot)) return false;
            for (var grant : grants()) {
                try {
                    if (stableRoot(Path.of(grant.directory)).equals(canonicalRoot)
                            && (kind == Kind.FILES ? grant.allowFiles : grant.allowCommands)) return true;
                } catch (IOException | IllegalArgumentException ignored) { /* Invalid or moved directories never auto-approve. */ }
            }
        } catch (IOException | IllegalArgumentException ignored) { /* Fail closed; the normal confirmation remains available. */ }
        return false;
    }

    void allow(Path root, Kind kind) throws IOException {
        synchronized (settings) {
            Path canonical = stableRoot(root);
            boolean files = false, commands = false;
            for (var grant : grants()) if (samePath(grant.directory, canonical)) {
                files |= grant.allowFiles; commands |= grant.allowCommands;
            }
            set(canonical, files || kind == Kind.FILES, commands || kind == Kind.COMMANDS);
        }
    }

    void set(Path root, boolean files, boolean commands) throws IOException {
        synchronized (settings) {
            Path canonical = stableRoot(root);
            var updated = new ArrayList<>(grants());
            updated.removeIf(grant -> samePath(grant.directory, canonical));
            if (files || commands) {
                var grant = new MollmSettings.DirectoryGrant();
                grant.directory = canonical.toString(); grant.allowFiles = files; grant.allowCommands = commands;
                updated.add(grant);
            }
            settings.getState().directoryGrants = List.copyOf(updated);
        }
    }

    /**
     * MCP auto-approval is deliberately narrower than directory command approval:
     * one workspace, one server ID + connection fingerprint, and one exact tool name.
     */
    boolean allowsMcp(Path root, MollmSettings.McpServerConfig server, String toolName) {
        if (server == null || !server.enabled || server.id == null || server.id.isBlank()
                || toolName == null || toolName.isBlank()) return false;
        try {
            Path canonical = stableRoot(root);
            String signature = mcpServerFingerprint(server);
            for (var grant : mcpGrants()) {
                if (grant != null && grant.serverId != null && grant.toolName != null
                        && grant.serverFingerprint != null && grant.directory != null
                        && server.id.equals(grant.serverId) && toolName.equals(grant.toolName)
                        && signature.equals(grant.serverFingerprint)
                        && samePath(grant.directory, canonical)) return true;
            }
        } catch (IOException | IllegalArgumentException ignored) { /* Fail closed if workspace changed. */ }
        return false;
    }

    void allowMcp(Path root, MollmSettings.McpServerConfig server, String toolName) throws IOException {
        if (server == null || !server.enabled || server.id == null || server.id.isBlank()
                || toolName == null || toolName.isBlank()) throw new IOException("MCP 工具或伺服器無效。");
        synchronized (settings) {
            Path canonical = stableRoot(root);
            var updated = new ArrayList<>(mcpGrants());
            updated.removeIf(grant -> grant != null && samePath(grant.directory, canonical)
                    && server.id.equals(grant.serverId) && toolName.equals(grant.toolName));
            var grant = new MollmSettings.McpToolGrant();
            grant.directory = canonical.toString();
            grant.serverId = server.id;
            grant.toolName = toolName;
            grant.serverFingerprint = mcpServerFingerprint(server);
            updated.add(grant);
            settings.getState().mcpToolGrants = List.copyOf(updated);
        }
    }

    List<MollmSettings.McpToolGrant> mcpGrants() {
        synchronized (settings) {
            var grants = settings.getState().mcpToolGrants;
            return grants == null ? List.of() : List.copyOf(grants);
        }
    }

    void revokeMcp(MollmSettings.McpToolGrant grant) {
        synchronized (settings) {
            settings.getState().mcpToolGrants = mcpGrants().stream()
                    .filter(existing -> existing != grant && !(java.util.Objects.equals(existing.directory, grant.directory)
                            && java.util.Objects.equals(existing.serverId, grant.serverId)
                            && java.util.Objects.equals(existing.toolName, grant.toolName))).toList();
        }
    }

    /** Hash connection settings without storing credentials in the permission grant. */
    private static String mcpServerFingerprint(MollmSettings.McpServerConfig server) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            hashField(hash, server.transport);
            hashField(hash, server.projectScope);
            hashField(hash, server.executable);
            hashField(hash, server.workingDirectory);
            hashField(hash, server.baseUrl);
            hashField(hash, Boolean.toString(server.passAuthToken));
            hashField(hash, Integer.toString(server.args.size()));
            for (String arg : server.args) hashField(hash, arg);
            for (var entry : new TreeMap<>(server.env).entrySet()) {
                hashField(hash, entry.getKey()); hashField(hash, entry.getValue());
            }
            for (var entry : new TreeMap<>(server.headers).entrySet()) {
                hashField(hash, entry.getKey()); hashField(hash, entry.getValue());
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void hashField(MessageDigest hash, String value) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        hash.update(new byte[]{(byte) (bytes.length >>> 24), (byte) (bytes.length >>> 16),
                (byte) (bytes.length >>> 8), (byte) bytes.length});
        hash.update(bytes);
    }

    List<MollmSettings.DirectoryGrant> grants() {
        synchronized (settings) {
            var grants = settings.getState().directoryGrants;
            return grants == null ? List.of() : List.copyOf(grants);
        }
    }

    void revoke(String directory) {
        synchronized (settings) {
            settings.getState().directoryGrants = grants().stream().filter(grant -> !grant.directory.equals(directory)).toList();
        }
    }

    static boolean samePath(String stored, Path expected) {
        try { return Path.of(stored).toAbsolutePath().normalize().equals(expected); }
        catch (IllegalArgumentException e) { return false; }
    }
    private static Path stableRoot(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize(), real = normalized.toRealPath();
        if (!normalized.equals(real) || !Files.isDirectory(real)) throw new IOException("工作目錄已變更或不是資料夾，請重新選擇。");
        return real;
    }
    private static Path canonicalTarget(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize(), existing = normalized;
        while (!Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            if (existing == null) throw new IOException("找不到目標的上層目錄。");
        }
        return existing.toRealPath().resolve(existing.relativize(normalized)).normalize();
    }
}
