package io.github.minakshikothari.jiramcp.web;

import io.github.minakshikothari.jiramcp.JiraClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The actual Jira actions behind the Chat tab, shared by both ways of
 * driving it:
 *   - CommandHandler: the original regex CommandParser maps a handful of
 *     fixed phrasings straight to one of these tools — deterministic, no
 *     network call to an LLM, works even with no ANTHROPIC_API_KEY set.
 *   - LlmCommandHandler: Claude reads free text and decides which tool to
 *     call and with what arguments (real natural-language understanding).
 *
 * Both end up calling exactly the same methods here, against the same
 * JiraClient — which is the point being demoed: the tool surface (what Jira
 * operations exist, and their JSON shape) is the stable, reusable part.
 * Only "who decides which tool to call" changes.
 *
 * Each method returns an ObjectNode shaped for the chat UI: a human-readable
 * "message", optional "issues"/"issueDetail" data, and — for the "Show MCP
 * tool call" panel — "toolName"/"toolArgs" recording what was actually run.
 */
public class JiraToolExecutor {

    private final JiraClient jira;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public JiraToolExecutor(JiraClient jira, String baseUrl) {
        this.jira = jira;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** Runs one named tool with the given JSON arguments. */
    public ObjectNode execute(String toolName, JsonNode args) throws Exception {
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        if (args == null) {
            args = mapper.createObjectNode();
        }

        switch (toolName == null ? "" : toolName) {
            case "search_issues" -> handleSearch(out, args.path("jql").asText(""));
            case "get_issue" -> handleGet(out, args.path("issueKey").asText(""));
            case "create_issue" -> handleCreate(out, args);
            case "add_comment" -> handleComment(out, args);
            case "transition_issue" -> handleTransition(out, args);
            case "close_issue" -> handleClose(out, args.path("issueKey").asText(""));
            case "add_issue_to_sprint" -> handleAddToSprint(out, args.path("issueKey").asText(""));
            case "remove_issue_from_sprint" -> handleRemoveFromSprint(out, args.path("issueKey").asText(""));
            default -> {
                out.put("ok", false);
                out.put("message", "Unknown tool: " + toolName);
            }
        }
        return out;
    }

    private void handleSearch(ObjectNode out, String jql) throws Exception {
        if (jql == null || jql.isBlank()) {
            out.put("ok", false);
            out.put("message", "No JQL query was given to search with.");
            return;
        }
        JsonNode result = jira.searchIssues(jql, 20);
        JsonNode issues = result.path("issues");

        ArrayNode issueArray = out.putArray("issues");
        for (JsonNode issue : issues) {
            JsonNode f = issue.path("fields");
            ObjectNode node = issueArray.addObject();
            String key = issue.path("key").asText();
            node.put("key", key);
            node.put("url", baseUrl + "/browse/" + key);
            node.put("status", f.path("status").path("name").asText("?"));
            node.put("summary", f.path("summary").asText(""));
            node.put("assignee", f.path("assignee").path("displayName").asText("Unassigned"));
            node.put("priority", f.path("priority").path("name").asText("?"));
        }

        int count = issueArray.size();
        out.put("message", count == 0
                ? "No issues matched that query."
                : "Found " + count + " issue" + (count == 1 ? "" : "s") + ".");

        addToolCall(out, "search_issues", mapper.createObjectNode()
                .put("jql", jql)
                .put("maxResults", 20));
    }

    private void handleGet(ObjectNode out, String issueKey) throws Exception {
        if (issueKey == null || issueKey.isBlank()) {
            out.put("ok", false);
            out.put("message", "No issue key was given.");
            return;
        }
        JsonNode issue = jira.getIssue(issueKey);
        JsonNode f = issue.path("fields");

        ObjectNode detail = out.putObject("issueDetail");
        detail.put("key", issue.path("key").asText());
        detail.put("url", baseUrl + "/browse/" + issue.path("key").asText());
        detail.put("summary", f.path("summary").asText(""));
        detail.put("status", f.path("status").path("name").asText("?"));
        detail.put("type", f.path("issuetype").path("name").asText("?"));
        detail.put("priority", f.path("priority").path("name").asText("?"));
        detail.put("assignee", f.path("assignee").path("displayName").asText("Unassigned"));
        detail.put("updated", f.path("updated").asText("?"));

        out.put("message", "Here's " + issueKey + ".");
        addToolCall(out, "get_issue", mapper.createObjectNode().put("issueKey", issueKey));
    }

    private void handleCreate(ObjectNode out, JsonNode args) throws Exception {
        String projectKey = args.path("projectKey").asText("");
        String issueType = args.path("issueType").asText("Task");
        String summary = args.path("summary").asText("");
        String description = args.path("description").asText("");

        if (projectKey.isBlank() || summary.isBlank()) {
            out.put("ok", false);
            out.put("message", "Need at least a project key and a summary to create an issue.");
            return;
        }

        JsonNode created = jira.createIssue(projectKey, issueType, summary, description);
        String key = created.path("key").asText("unknown");

        ObjectNode detail = out.putObject("issueDetail");
        detail.put("key", key);
        detail.put("url", baseUrl + "/browse/" + key);
        detail.put("summary", summary);
        detail.put("status", "New");
        detail.put("type", issueType);

        out.put("message", "Created " + key + ": " + summary);
        addToolCall(out, "create_issue", mapper.createObjectNode()
                .put("projectKey", projectKey)
                .put("issueType", issueType)
                .put("summary", summary)
                .put("description", description));
    }

    private void handleComment(ObjectNode out, JsonNode args) throws Exception {
        String issueKey = args.path("issueKey").asText("");
        String comment = args.path("comment").asText("");
        if (issueKey.isBlank() || comment.isBlank()) {
            out.put("ok", false);
            out.put("message", "Need both an issue key and comment text.");
            return;
        }
        jira.addComment(issueKey, comment);

        out.put("message", "Comment added to " + issueKey + ".");
        addToolCall(out, "add_comment", mapper.createObjectNode()
                .put("issueKey", issueKey)
                .put("comment", comment));
    }

    private void handleTransition(ObjectNode out, JsonNode args) throws Exception {
        String issueKey = args.path("issueKey").asText("");
        String targetName = args.path("transitionName").asText("");

        JsonNode transitions = jira.listTransitions(issueKey).path("transitions");
        String matchId = null;
        StringBuilder available = new StringBuilder();
        for (JsonNode t : transitions) {
            String name = t.path("name").asText("");
            if (available.length() > 0) available.append(", ");
            available.append(name);
            if (name.equalsIgnoreCase(targetName)) {
                matchId = t.path("id").asText();
            }
        }

        if (matchId == null) {
            out.put("ok", false);
            out.put("message", "No transition named \"" + targetName + "\" is available for " + issueKey
                    + ". Available: " + available);
            return;
        }

        jira.transitionIssue(issueKey, matchId);
        out.put("message", issueKey + " moved to \"" + targetName + "\".");
        addToolCall(out, "transition_issue", mapper.createObjectNode()
                .put("issueKey", issueKey)
                .put("transitionName", targetName));
    }

    private void handleClose(ObjectNode out, String issueKey) throws Exception {
        if (issueKey == null || issueKey.isBlank()) {
            out.put("ok", false);
            out.put("message", "No issue key was given.");
            return;
        }
        JsonNode transitions = jira.listTransitions(issueKey).path("transitions");
        String matchId = null;
        String matchName = null;
        StringBuilder available = new StringBuilder();
        for (JsonNode t : transitions) {
            String name = t.path("name").asText("");
            if (available.length() > 0) available.append(", ");
            available.append(name);
            String lower = name.toLowerCase();
            if (matchId == null && (lower.contains("close") || lower.contains("done") || lower.contains("resolve"))) {
                matchId = t.path("id").asText();
                matchName = name;
            }
        }

        if (matchId == null) {
            out.put("ok", false);
            out.put("message", "Couldn't find a \"Close/Done\" transition for " + issueKey
                    + ". Available transitions: " + available + ". Try moving it to an exact status name instead.");
            return;
        }

        jira.transitionIssue(issueKey, matchId);
        out.put("message", "Closed " + issueKey + " (moved to \"" + matchName + "\").");
        addToolCall(out, "transition_issue", mapper.createObjectNode()
                .put("issueKey", issueKey)
                .put("transitionName", matchName));
    }

    private void handleAddToSprint(ObjectNode out, String issueKey) throws Exception {
        if (issueKey == null || issueKey.isBlank() || !issueKey.contains("-")) {
            out.put("ok", false);
            out.put("message", "No valid issue key was given.");
            return;
        }
        String projectKey = issueKey.split("-")[0];
        JsonNode sprint = jira.getActiveSprint(projectKey);
        int sprintId = sprint.path("id").asInt();
        String sprintName = sprint.path("name").asText("current sprint");

        jira.addIssueToSprint(sprintId, issueKey);
        out.put("message", "Added " + issueKey + " to \"" + sprintName + "\".");
        addToolCall(out, "add_issue_to_sprint", mapper.createObjectNode()
                .put("issueKey", issueKey)
                .put("sprintId", sprintId)
                .put("sprintName", sprintName));
    }

    private void handleRemoveFromSprint(ObjectNode out, String issueKey) throws Exception {
        if (issueKey == null || issueKey.isBlank()) {
            out.put("ok", false);
            out.put("message", "No issue key was given.");
            return;
        }
        jira.moveIssueToBacklog(issueKey);
        out.put("message", "Removed " + issueKey + " from its sprint (moved to backlog).");
        addToolCall(out, "remove_issue_from_sprint", mapper.createObjectNode()
                .put("issueKey", issueKey));
    }

    private void addToolCall(ObjectNode out, String toolName, ObjectNode args) {
        out.put("toolName", toolName);
        out.set("toolArgs", args);
    }
}
