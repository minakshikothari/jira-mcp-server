package io.github.minakshikothari.jiramcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Thin wrapper around the Jira Server/Data Center REST API (v2).
 *
 * This is intentionally dependency-light (java.net.http instead of a Jira SDK)
 * so the whole POC is easy to read end-to-end in a management demo: every
 * MCP "tool" maps to exactly one HTTP call here.
 *
 * Auth: Jira Data Center Personal Access Token, sent as a Bearer header.
 * Create one under your Jira profile: Profile picture -> Personal Access Tokens.
 * (Jira Cloud instead uses Basic base64(email:api_token) against /rest/api/3 —
 * if you ever point this at a *.atlassian.net site, swap the auth back.)
 */
public class JiraClient {

    private final String baseUrl;      // e.g. https://jira.example.com
    private final String authHeader;   // "Bearer <personal-access-token>"
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public JiraClient(String baseUrl, String personalAccessToken) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.authHeader = "Bearer " + personalAccessToken;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30));
    }

    private JsonNode send(HttpRequest req) throws Exception {
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restore the flag the JDK cleared
            throw e;
        }
        if (resp.statusCode() >= 300) {
            throw new RuntimeException("Jira API error " + resp.statusCode() + ": " + resp.body());
        }
        if (resp.body() == null || resp.body().isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(resp.body());
    }

    /**
     * Search issues with a JQL query. Returns the raw Jira search result JSON.
     * Fields requested include statusCategory (nested inside "status") for
     * generically bucketing custom workflow statuses into To Do / In Progress /
     * Done, plus created/resolutiondate for date-range dashboards.
     */
    public JsonNode searchIssues(String jql, int maxResults) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("jql", jql);
        body.put("maxResults", maxResults);
        body.putArray("fields").add("summary").add("status").add("assignee")
                .add("priority").add("issuetype").add("updated")
                .add("created").add("resolutiondate").add("customfield_10005");

        HttpRequest req = request("/rest/api/2/search")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return send(req);
    }

    /** Fetch the currently authenticated user (used to highlight "you" in comparisons). */
    public JsonNode getCurrentUser() throws Exception {
        HttpRequest req = request("/rest/api/2/myself").GET().build();
        return send(req);
    }

    /** Fetch a single issue by key (e.g. "PLAT-123"). */
    public JsonNode getIssue(String issueKey) throws Exception {
        HttpRequest req = request("/rest/api/2/issue/" + issueKey).GET().build();
        return send(req);
    }

    /** Create a new issue in the given project. */
    public JsonNode createIssue(String projectKey, String issueType, String summary, String description) throws Exception {
        ObjectNode fields = mapper.createObjectNode();
        fields.putObject("project").put("key", projectKey);
        fields.put("summary", summary);
        fields.putObject("issuetype").put("name", issueType);

        // v2 API takes description as a plain string (no ADF, unlike Cloud's v3).
        if (description != null && !description.isBlank()) {
            fields.put("description", description);
        }

        ObjectNode body = mapper.createObjectNode();
        body.set("fields", fields);

        HttpRequest req = request("/rest/api/2/issue")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return send(req);
    }

    /** Add a plain-text comment to an issue. */
    public JsonNode addComment(String issueKey, String commentText) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("body", commentText);

        HttpRequest req = request("/rest/api/2/issue/" + issueKey + "/comment")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return send(req);
    }

    /**
     * Finds the currently active sprint for a project, via the Jira Agile
     * ("GreenHopper") REST API: look up the project's Scrum board(s), then
     * the first active sprint on any of them. Used so a chat command like
     * "add X to the current sprint" doesn't need the sprint ID spelled out.
     */
    public JsonNode getActiveSprint(String projectKey) throws Exception {
        HttpRequest boardsReq = request("/rest/agile/1.0/board?projectKeyOrId=" + projectKey).GET().build();
        JsonNode boards = send(boardsReq).path("values");
        if (!boards.isEmpty()) {
            for (JsonNode board : boards) {
                int boardId = board.path("id").asInt();
                HttpRequest sprintsReq = request("/rest/agile/1.0/board/" + boardId + "/sprint?state=active").GET().build();
                JsonNode sprints = send(sprintsReq).path("values");
                if (sprints.size() > 0) {
                    return sprints.get(0);
                }
            }
        }
        throw new RuntimeException("No active sprint found for project " + projectKey
                + " (no Scrum board, or no sprint currently active).");
    }

    /** Adds an issue to the given sprint (moves it out of the backlog if needed). */
    public void addIssueToSprint(int sprintId, String issueKey) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.putArray("issues").add(issueKey);
        HttpRequest req = request("/rest/agile/1.0/sprint/" + sprintId + "/issue")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        send(req);
    }

    /** Removes an issue from its sprint by moving it back to the backlog. */
    public void moveIssueToBacklog(String issueKey) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.putArray("issues").add(issueKey);
        HttpRequest req = request("/rest/agile/1.0/backlog/issue")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        send(req);
    }

    /** List the workflow transitions currently available for an issue. */
    public JsonNode listTransitions(String issueKey) throws Exception {
        HttpRequest req = request("/rest/api/2/issue/" + issueKey + "/transitions").GET().build();
        return send(req);
    }

    /** Move an issue through its workflow (e.g. "In Progress" -> "Done") by transition id. */
    public void transitionIssue(String issueKey, String transitionId) throws Exception {
        ObjectNode transition = mapper.createObjectNode();
        transition.put("id", transitionId);
        ObjectNode body = mapper.createObjectNode();
        body.set("transition", transition);

        HttpRequest req = request("/rest/api/2/issue/" + issueKey + "/transitions")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        send(req);
    }
}
