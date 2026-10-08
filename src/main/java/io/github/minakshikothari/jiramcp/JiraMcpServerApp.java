package io.github.minakshikothari.jiramcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Jira MCP Server — proof of concept.
 *
 * Starts an MCP server over stdio (the same transport Claude Desktop /
 * Claude Code use for local tools) that exposes Jira operations as tools
 * an AI agent can call directly: search_issues, get_issue, create_issue,
 * add_comment, transition_issue.
 *
 * Config (env vars):
 *   JIRA_BASE_URL      e.g. https://jira.example.com
 *   JIRA_ACCESS_TOKEN  Personal Access Token from your Jira profile
 *                       (Profile picture -> Personal Access Tokens -> Create token)
 *
 * Run:
 *   mvn clean package
 *   JIRA_BASE_URL=... JIRA_ACCESS_TOKEN=... java -jar target/jira-mcp-server.jar
 *
 * Then point an MCP client at it (see README for the Claude Desktop config
 * snippet) and ask it things like:
 *   "What's open and unassigned in the PLAT project?"
 *   "Create a bug in PLAT: login fails on expired session token"
 *   "Move PLAT-482 to Done and leave a comment saying it's verified in staging"
 */
public class JiraMcpServerApp {

    public static void main(String[] args) {
        String baseUrl = requireEnv("JIRA_BASE_URL");
        String accessToken = requireEnv("JIRA_ACCESS_TOKEN");

        JiraClient jiraClient = new JiraClient(baseUrl, accessToken);
        JiraTools tools = new JiraTools(jiraClient);

        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider();

        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("jira-mcp-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .build();

        for (McpServerFeatures.SyncToolSpecification tool : tools.buildAll()) {
            server.addTool(tool);
        }

        // Server now blocks, handling JSON-RPC messages over stdin/stdout
        // until the client disconnects. Logging must go to stderr only —
        // stdout is reserved for the protocol.
        System.err.println("jira-mcp-server ready: 5 tools registered, listening on stdio");
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
