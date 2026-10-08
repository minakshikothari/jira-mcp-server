package io.github.minakshikothari.jiramcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * Builds the set of MCP tools this server exposes.
 *
 * Each tool = a name + a JSON-schema description of its arguments (so any
 * MCP-aware LLM client can figure out how to call it) + a handler that
 * translates the call into a JiraClient method and returns a
 * McpSchema.CallToolResult with the answer as text.
 *
 * This is the part worth walking management through: five tools here
 * replace what would otherwise be five bespoke REST endpoints, five sets of
 * API docs, and manual wiring into whatever internal chat/agent tool wants
 * to touch Jira. Any MCP client (Claude, an internal copilot, etc.) gets
 * all five for free the moment it connects to this process.
 */
public class JiraTools {

    private final JiraClient jira;

    public JiraTools(JiraClient jira) {
        this.jira = jira;
    }

    public List<McpServerFeatures.SyncToolSpecification> buildAll() {
        return List.of(
                searchIssuesTool(),
                getIssueTool(),
                createIssueTool(),
                addCommentTool(),
                transitionIssueTool()
        );
    }

    private McpServerFeatures.SyncToolSpecification searchIssuesTool() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "jql": { "type": "string", "description": "Jira Query Language filter, e.g. 'project = PLAT AND status = \\"In Progress\\"'" },
                "maxResults": { "type": "integer", "description": "Max issues to return", "default": 20 }
              },
              "required": ["jql"]
            }
            """;
        McpSchema.Tool tool = new McpSchema.Tool(
                "search_issues",
                "Search Jira issues using a JQL query. Returns key, summary, status, assignee and priority for each match.",
                schema
        );
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            try {
                String jql = (String) args.get("jql");
                int maxResults = args.containsKey("maxResults")
                        ? ((Number) args.get("maxResults")).intValue() : 20;
                JsonNode result = jira.searchIssues(jql, maxResults);
                return textResult(summarizeSearch(result));
            } catch (Exception e) {
                return errorResult(e);
            }
        });
    }

    private McpServerFeatures.SyncToolSpecification getIssueTool() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "issueKey": { "type": "string", "description": "Jira issue key, e.g. 'PLAT-123'" }
              },
              "required": ["issueKey"]
            }
            """;
        McpSchema.Tool tool = new McpSchema.Tool(
                "get_issue",
                "Fetch full details for a single Jira issue by its key.",
                schema
        );
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            try {
                String key = (String) args.get("issueKey");
                JsonNode issue = jira.getIssue(key);
                return textResult(summarizeIssue(issue));
            } catch (Exception e) {
                return errorResult(e);
            }
        });
    }

    private McpServerFeatures.SyncToolSpecification createIssueTool() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "projectKey": { "type": "string", "description": "Project key, e.g. 'PLAT'" },
                "issueType": { "type": "string", "description": "e.g. 'Task', 'Bug', 'Story'", "default": "Task" },
                "summary": { "type": "string" },
                "description": { "type": "string" }
              },
              "required": ["projectKey", "summary"]
            }
            """;
        McpSchema.Tool tool = new McpSchema.Tool(
                "create_issue",
                "Create a new Jira issue in a given project.",
                schema
        );
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            try {
                String projectKey = (String) args.get("projectKey");
                String issueType = args.getOrDefault("issueType", "Task").toString();
                String summary = (String) args.get("summary");
                String description = (String) args.getOrDefault("description", "");
                JsonNode created = jira.createIssue(projectKey, issueType, summary, description);
                String key = created.path("key").asText("unknown");
                return textResult("Created issue " + key);
            } catch (Exception e) {
                return errorResult(e);
            }
        });
    }

    private McpServerFeatures.SyncToolSpecification addCommentTool() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "issueKey": { "type": "string" },
                "comment": { "type": "string" }
              },
              "required": ["issueKey", "comment"]
            }
            """;
        McpSchema.Tool tool = new McpSchema.Tool(
                "add_comment",
                "Add a plain-text comment to an existing Jira issue.",
                schema
        );
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            try {
                String key = (String) args.get("issueKey");
                String comment = (String) args.get("comment");
                jira.addComment(key, comment);
                return textResult("Comment added to " + key);
            } catch (Exception e) {
                return errorResult(e);
            }
        });
    }

    private McpServerFeatures.SyncToolSpecification transitionIssueTool() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "issueKey": { "type": "string" },
                "transitionName": { "type": "string", "description": "Target status name, e.g. 'Done', 'In Review'" }
              },
              "required": ["issueKey", "transitionName"]
            }
            """;
        McpSchema.Tool tool = new McpSchema.Tool(
                "transition_issue",
                "Move a Jira issue to a new status (e.g. 'In Progress' -> 'Done'). Looks up the matching transition id automatically.",
                schema
        );
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            try {
                String key = (String) args.get("issueKey");
                String targetName = (String) args.get("transitionName");
                JsonNode transitions = jira.listTransitions(key).path("transitions");

                String matchId = null;
                for (JsonNode t : transitions) {
                    if (t.path("name").asText("").equalsIgnoreCase(targetName)) {
                        matchId = t.path("id").asText();
                        break;
                    }
                }
                if (matchId == null) {
                    return textResult("No transition named '" + targetName + "' is available for " + key
                            + ". Available: " + transitionNames(transitions));
                }
                jira.transitionIssue(key, matchId);
                return textResult(key + " moved to '" + targetName + "'");
            } catch (Exception e) {
                return errorResult(e);
            }
        });
    }

    // ---- formatting helpers -------------------------------------------------

    private String summarizeSearch(JsonNode result) {
        JsonNode issues = result.path("issues");
        if (!issues.isArray() || issues.isEmpty()) {
            return "No issues matched.";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode issue : issues) {
            JsonNode f = issue.path("fields");
            sb.append(issue.path("key").asText())
                    .append(" [").append(f.path("status").path("name").asText("?")).append("] ")
                    .append(f.path("summary").asText(""))
                    .append(" (assignee: ")
                    .append(f.path("assignee").path("displayName").asText("unassigned"))
                    .append(")\n");
        }
        return sb.toString().strip();
    }

    private String summarizeIssue(JsonNode issue) {
        JsonNode f = issue.path("fields");
        return issue.path("key").asText() + " - " + f.path("summary").asText("") + "\n"
                + "Status: " + f.path("status").path("name").asText("?") + "\n"
                + "Type: " + f.path("issuetype").path("name").asText("?") + "\n"
                + "Priority: " + f.path("priority").path("name").asText("?") + "\n"
                + "Assignee: " + f.path("assignee").path("displayName").asText("unassigned") + "\n"
                + "Updated: " + f.path("updated").asText("?");
    }

    private String transitionNames(JsonNode transitions) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode t : transitions) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(t.path("name").asText());
        }
        return sb.toString();
    }

    private McpSchema.CallToolResult textResult(String text) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), false);
    }

    private McpSchema.CallToolResult errorResult(Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
    }
}
