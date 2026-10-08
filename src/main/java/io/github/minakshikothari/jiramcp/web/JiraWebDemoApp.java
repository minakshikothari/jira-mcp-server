package io.github.minakshikothari.jiramcp.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.minakshikothari.jiramcp.JiraClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Standalone browser demo for the Jira MCP proof of concept.
 *
 * This is a SEPARATE way to run the same underlying JiraClient used by the
 * MCP server (JiraMcpServerApp) — instead of talking JSON-RPC over stdio to
 * Claude Desktop, it serves a small web app on localhost so you can demo
 * "type a command, see it turn into a Jira tool call" (the Chat tab) and a
 * sprint/status dashboard (the Dashboard tab) to anyone, without needing
 * Claude Desktop open at all.
 *
 * Config (env vars): same as the MCP server, plus one optional addition —
 *   JIRA_BASE_URL      e.g. https://jira.example.com
 *   JIRA_ACCESS_TOKEN  Personal Access Token from your Jira profile
 *   WEB_PORT           optional, defaults to 4321
 *   ANTHROPIC_API_KEY  optional — turns on "AI mode" in the Chat tab, where
 *                      Claude (not a regex parser) decides which Jira tool to
 *                      call from whatever you type. Without it, the Chat tab
 *                      still works using the built-in fixed-phrase parser.
 *
 * Run:
 *   mvn clean package
 *   JIRA_BASE_URL=... JIRA_ACCESS_TOKEN=... [ANTHROPIC_API_KEY=...] java -cp target/jira-mcp-server.jar io.github.minakshikothari.jiramcp.web.JiraWebDemoApp
 *   then open http://localhost:4321
 *
 *   By default this only listens on 127.0.0.1 (this machine only) — safest
 *   default, since there's no login on this demo and it can create/modify
 *   real Jira issues. To let another machine on the same network open it
 *   (e.g. a Windows laptop in the same room during a demo), set:
 *     WEB_HOST=0.0.0.0
 *   and browse to http://<this Mac's LAN IP>:<port> from the other machine.
 */
public class JiraWebDemoApp {

    public static void main(String[] args) throws IOException {
        String baseUrl = requireEnv("JIRA_BASE_URL");
        String accessToken = requireEnv("JIRA_ACCESS_TOKEN");
        int port = Integer.parseInt(System.getenv().getOrDefault("WEB_PORT", "4321"));
        String host = System.getenv().getOrDefault("WEB_HOST", "127.0.0.1");
        String anthropicApiKey = System.getenv("ANTHROPIC_API_KEY");

        JiraClient jiraClient = new JiraClient(baseUrl, accessToken);
        JiraToolExecutor toolExecutor = new JiraToolExecutor(jiraClient, baseUrl);
        CommandHandler commandHandler = new CommandHandler(jiraClient, baseUrl);
        DashboardHandler dashboardHandler = new DashboardHandler(jiraClient, baseUrl);
        ObjectMapper mapper = new ObjectMapper();

        boolean aiAvailable = anthropicApiKey != null && !anthropicApiKey.isBlank();
        LlmCommandHandler llmCommandHandler = aiAvailable
                ? new LlmCommandHandler(new AnthropicClient(anthropicApiKey), toolExecutor)
                : null;

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.createContext("/api/config", exchange ->
                handlePost(exchange, mapper, body -> {
                    ObjectNode cfg = mapper.createObjectNode();
                    cfg.put("aiAvailable", aiAvailable);
                    return cfg;
                }));

        server.createContext("/api/reset", exchange ->
                handlePost(exchange, mapper, body -> {
                    if (llmCommandHandler != null) {
                        llmCommandHandler.resetConversation();
                    }
                    ObjectNode ack = mapper.createObjectNode();
                    ack.put("ok", true);
                    return ack;
                }));

        server.createContext("/api/command", exchange ->
                handlePost(exchange, mapper, body -> {
                    boolean aiMode = body.path("aiMode").asBoolean(false);
                    String message = body.path("message").asText("");
                    if (aiMode) {
                        if (!aiAvailable) {
                            ObjectNode err = mapper.createObjectNode();
                            err.put("ok", false);
                            err.put("message", "AI mode isn't configured — set ANTHROPIC_API_KEY and restart "
                                    + "the demo to enable it. Falling back to fixed commands for now.");
                            return err;
                        }
                        return llmCommandHandler.handle(message);
                    }
                    return commandHandler.handle(message);
                }));

        server.createContext("/api/dashboard", exchange ->
                handlePost(exchange, mapper, body -> dashboardHandler.build(
                        body.path("projectKey").asText(""),
                        body.path("currentSprintOnly").asBoolean(false),
                        body.path("onlyMyTickets").asBoolean(true),
                        body.path("assignee").asText(""),
                        body.path("issueType").asText(""),
                        body.path("dateField").asText("created"),
                        body.path("fromDate").asText(""),
                        body.path("toDate").asText(""))));

        server.createContext("/", JiraWebDemoApp::serveStatic);

        server.setExecutor(null); // default executor is fine for a demo
        server.start();

        System.out.println("Jira MCP demo UI running: http://localhost:" + port);
        if ("0.0.0.0".equals(host)) {
            System.out.println("Also reachable from other machines on this network at: http://<this Mac's LAN IP>:" + port);
            System.out.println("Find that IP with: ipconfig getifaddr en0  (or check System Settings > Wi-Fi > Details)");
        }
        System.out.println("Connected to: " + baseUrl);
    }

    @FunctionalInterface
    private interface JsonHandler {
        ObjectNode handle(JsonNode requestBody) throws Exception;
    }

    private static void handlePost(HttpExchange exchange, ObjectMapper mapper, JsonHandler handler) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        ObjectNode response;
        try (InputStream is = exchange.getRequestBody()) {
            JsonNode body = mapper.readTree(is);
            response = handler.handle(body);
        } catch (Exception e) {
            response = mapper.createObjectNode();
            response.put("ok", false);
            response.put("message", "Server error: " + e.getMessage());
        }
        byte[] bytes = mapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void serveStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }
        String resourcePath = "/web" + path;

        InputStream in = JiraWebDemoApp.class.getResourceAsStream(resourcePath);
        if (in == null) {
            String notFound = "404 Not Found: " + path;
            exchange.sendResponseHeaders(404, notFound.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(notFound.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        exchange.getResponseHeaders().add("Content-Type", contentType(path));
        byte[] bytes = in.readAllBytes();
        in.close();
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        return "application/octet-stream";
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.err.println("Missing required environment variable: " + name);
            System.exit(1);
        }
        return value;
    }
}
