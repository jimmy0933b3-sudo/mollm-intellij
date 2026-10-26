package tw.com.fmt.mollm;

import com.intellij.openapi.project.Project;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


final class McpConnectionManager {
    private static final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    static void initialize(Project project) {
        // Called when project opens or settings change.
        // We do lazy initialization during tool usage to avoid hanging startup.
        var configs = MollmSettings.getInstance().mcpServers();
        // Remove disabled or deleted servers
        java.util.Set<String> activeIds = configs.stream()
                .filter(c -> c.enabled)
                .map(c -> c.id)
                .collect(Collectors.toSet());

        List<String> toClose = new ArrayList<>();
        for (String id : clients.keySet()) {
            if (!activeIds.contains(id)) toClose.add(id);
        }
        toClose.forEach(McpConnectionManager::close);
    }

    static synchronized McpSyncClient getClient(MollmSettings.McpServerConfig config) {
        if (!config.enabled) return null;
        if (clients.containsKey(config.id)) {
            return clients.get(config.id);
        }

        McpSyncClient client = null;
        try {
            if ("stdio".equals(config.transport)) {
                var builder = ServerParameters.builder(config.executable)
                        .args(config.args.toArray(new String[0]));
                Map<String, String> resolvedEnv = new HashMap<>(config.env);
                for (Map.Entry<String, String> entry : resolvedEnv.entrySet()) {
                    if ("__SECRET__".equals(entry.getValue())) {
                        entry.setValue(ApiKeys.get("mcp_env_" + config.id + "_" + entry.getKey()));
                    }
                }

                if (!resolvedEnv.isEmpty()) {
                    builder.env(resolvedEnv);
                }
                // MCP Java SDK 0.7 does not expose workingDirectory on ServerParameters.
                // Configure the underlying process instead, preserving the user's stdio cwd.
                var transport = new StdioClientTransport(builder.build()) {
                    @Override
                    protected ProcessBuilder getProcessBuilder() {
                        ProcessBuilder process = super.getProcessBuilder();
                        if (!config.workingDirectory.isBlank()) {
                            process.directory(new java.io.File(config.workingDirectory));
                        }
                        return process;
                    }
                };
                client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(config.requestTimeoutSeconds)).build();
            } else if ("sse".equals(config.transport)) {
                var webClientBuilder = WebClient.builder().baseUrl(config.baseUrl);

                Map<String, String> resolvedHeaders = new HashMap<>(config.headers);
                for (Map.Entry<String, String> entry : resolvedHeaders.entrySet()) {
                    if ("__SECRET__".equals(entry.getValue())) {
                        entry.setValue(ApiKeys.get("mcp_header_" + config.id + "_" + entry.getKey()));
                    }
                }

                webClientBuilder.defaultHeaders(httpHeaders -> {
                    for (var entry : resolvedHeaders.entrySet()) {
                        httpHeaders.add(entry.getKey(), entry.getValue());
                    }

                    // Inject auth token from PasswordSafe if requested
                    if (config.passAuthToken) {
                        String token = ApiKeys.get(config.id);
                        if (token != null && !token.isBlank()) {
                            // Automatically prefix with Bearer if there's no custom Authorization scheme already
                            if (!token.startsWith("Bearer ") && !token.startsWith("Basic ") && !resolvedHeaders.containsKey("Authorization")) {
                                httpHeaders.add("Authorization", "Bearer " + token);
                            } else {
                                httpHeaders.add("Authorization", token);
                            }
                        }
                    }
                });

                var transport = new WebFluxSseClientTransport(webClientBuilder);
                client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(config.requestTimeoutSeconds)).build();
            }

            if (client != null) {
                client.initialize();
                clients.put(config.id, client);
            }
        } catch (Exception e) {
            // Failed to start
            if (client != null) client.closeGracefully();
            throw new RuntimeException("Failed to initialize MCP Server: " + config.name, e);
        }

        return client;
    }


    static Map<String, McpSyncClient> getActiveClients() {
        var configs = MollmSettings.getInstance().mcpServers();
        Map<String, McpSyncClient> active = new HashMap<>();
        for (var config : configs) {
            if (config.enabled) {
                var client = getClient(config);
                if (client != null) active.put(config.id, client);
            }
        }
        return active;
    }

    static synchronized void close(String serverId) {
        var client = clients.remove(serverId);
        if (client != null) {
            try {
                client.closeGracefully();
            } catch (Exception ignored) {}
        }
    }

    static synchronized void closeAll() {
        for (var client : clients.values()) {
            try {
                client.closeGracefully();
            } catch (Exception ignored) {}
        }
        clients.clear();
    }
}
