package io.github.minakshikothari.jiramcp.web;

import io.github.minakshikothari.jiramcp.JiraClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * Builds the data behind the sprint/status dashboard: runs one JQL search
 * (project + optional "current sprint" + optional assignee + optional issue
 * type + optional date range) and aggregates the results into status-category
 * counts (To Do / In Progress / Done), a per-literal-status breakdown (since
 * real workflows use custom status names like "Rootcause Analysis" or
 * "Certification", not just the 3 standard ones), and the raw issue list for
 * a table.
 *
 * statusCategory is bucketed generically off Jira's own category key
 * ("new" / "indeterminate" / "done") rather than guessing at status names,
 * so this works regardless of how a project's workflow is configured.
 */
public class DashboardHandler {

    private final JiraClient jira;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public DashboardHandler(JiraClient jira, String baseUrl) {
        this.jira = jira;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public ObjectNode build(String projectKey, boolean currentSprintOnly,
                             boolean onlyMyTickets, String assignee, String issueType,
                             String dateField, String fromDate, String toDate) {
        ObjectNode out = mapper.createObjectNode();

        if (projectKey == null || projectKey.isBlank()) {
            out.put("ok", false);
            out.put("message", "Enter a project key (e.g. PROJ).");
            return out;
        }

        StringBuilder jql = new StringBuilder("project = ").append(projectKey.trim().toUpperCase());

        if (currentSprintOnly) {
            jql.append(" AND sprint in openSprints()");
        }

        if (onlyMyTickets) {
            jql.append(" AND assignee = currentUser()");
        } else if (assignee != null && !assignee.isBlank()) {
            jql.append(" AND assignee = \"").append(assignee.trim()).append("\"");
        }

        if (issueType != null && !issueType.isBlank() && !issueType.equalsIgnoreCase("All")) {
            jql.append(" AND issuetype = \"").append(issueType.trim()).append("\"");
        }

        String field = switch (dateField == null ? "created" : dateField) {
            case "updated" -> "updated";
            case "resolved" -> "resolutiondate";
            default -> "created";
        };
        if (fromDate != null && !fromDate.isBlank()) {
            jql.append(" AND ").append(field).append(" >= \"").append(fromDate.trim()).append("\"");
        }
        if (toDate != null && !toDate.isBlank()) {
            jql.append(" AND ").append(field).append(" <= \"").append(toDate.trim()).append("\"");
        }
        jql.append(" ORDER BY updated DESC");
        String jqlString = jql.toString();

        try {
            JsonNode result = jira.searchIssues(jqlString, 200);
            JsonNode issues = result.path("issues");

            // To Do / In Progress / Done — generic, based on Jira's own category key.
            Map<String, Integer> categoryCounts = new LinkedHashMap<>();
            categoryCounts.put("To Do", 0);
            categoryCounts.put("In Progress", 0);
            categoryCounts.put("Done", 0);

            // Per literal status name (custom workflow states), for the ranked bar list.
            Map<String, Integer> statusCounts = new LinkedHashMap<>();

            ArrayNode issueArray = out.putArray("issues");

            for (JsonNode issue : issues) {
                JsonNode f = issue.path("fields");
                String key = issue.path("key").asText();
                String statusName = f.path("status").path("name").asText("Unknown");
                String categoryKey = f.path("status").path("statusCategory").path("key").asText("new");

                String bucket = switch (categoryKey) {
                    case "done" -> "Done";
                    case "indeterminate" -> "In Progress";
                    default -> "To Do";
                };
                categoryCounts.merge(bucket, 1, Integer::sum);
                statusCounts.merge(statusName, 1, Integer::sum);

                ObjectNode node = issueArray.addObject();
                node.put("key", key);
                node.put("url", baseUrl + "/browse/" + key);
                node.put("summary", f.path("summary").asText(""));
                node.put("status", statusName);
                node.put("statusCategory", bucket);
                node.put("assignee", f.path("assignee").path("displayName").asText("Unassigned"));
                node.put("priority", f.path("priority").path("name").asText("?"));
                node.put("issueType", f.path("issuetype").path("name").asText("?"));
                node.put("updated", f.path("updated").asText("?"));
                node.put("created", f.path("created").asText("?"));
                JsonNode spNode = f.path("customfield_10005");
                node.put("storyPoints", spNode.isNumber() ? spNode.asDouble() : 0.0);
            }

            ArrayNode categoryArray = out.putArray("categoryCounts");
            categoryCounts.forEach((name, count) -> {
                ObjectNode c = categoryArray.addObject();
                c.put("name", name);
                c.put("count", count);
            });

            // Rank statuses by count desc, cap to top 8, fold the rest into "Other".
            ArrayNode statusArray = out.putArray("statusCounts");
            statusCounts.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(8)
                    .forEach(e -> {
                        ObjectNode s = statusArray.addObject();
                        s.put("name", e.getKey());
                        s.put("count", e.getValue());
                    });
            if (statusCounts.size() > 8) {
                int otherCount = statusCounts.entrySet().stream()
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .skip(8)
                        .mapToInt(Map.Entry::getValue)
                        .sum();
                ObjectNode s = statusArray.addObject();
                s.put("name", "Other");
                s.put("count", otherCount);
            }

            out.put("ok", true);
            out.put("total", issueArray.size());
            out.put("jql", jqlString);

            // Workload-by-assignee comparison: deliberately ignores the "only my
            // tickets" / assignee filter above (the whole point is comparing
            // yourself to others), but keeps project + sprint + issue type + date
            // scoping, so it answers "how am I doing this sprint vs the team".
            buildWorkload(out, projectKey, currentSprintOnly, issueType, dateField, fromDate, toDate);

        } catch (Exception e) {
            out.put("ok", false);
            String msg = e.getMessage();
            if (currentSprintOnly && msg != null && msg.toLowerCase().contains("sprint")) {
                out.put("message", "This project may not have an active Scrum sprint (or isn't a Scrum board). "
                        + "Try unchecking \"Current sprint only\". Details: " + msg);
            } else if (!onlyMyTickets && assignee != null && !assignee.isBlank()
                    && msg != null && msg.toLowerCase().contains("assignee")) {
                out.put("message", "Couldn't find a user matching \"" + assignee + "\". "
                        + "Try their exact Jira username. Details: " + msg);
            } else {
                out.put("message", "Jira error: " + msg);
            }
            out.put("jql", jqlString);
        }

        return out;
    }

    /**
     * Aggregates total Story Points (customfield_10005) and ticket count per
     * assignee, scoped by project/sprint/issue-type/date but NOT by "only my
     * tickets" — so the current user can see their capacity relative to
     * teammates for the same sprint. Adds a "workload" array to `out` plus
     * "workloadJql" and "currentUser" (best-effort, for highlighting "you").
     */
    private void buildWorkload(ObjectNode out, String projectKey, boolean currentSprintOnly,
                                String issueType, String dateField, String fromDate, String toDate) {
        StringBuilder wJql = new StringBuilder("project = ").append(projectKey.trim().toUpperCase());
        if (currentSprintOnly) {
            wJql.append(" AND sprint in openSprints()");
        }
        if (issueType != null && !issueType.isBlank() && !issueType.equalsIgnoreCase("All")) {
            wJql.append(" AND issuetype = \"").append(issueType.trim()).append("\"");
        }
        String field = switch (dateField == null ? "created" : dateField) {
            case "updated" -> "updated";
            case "resolved" -> "resolutiondate";
            default -> "created";
        };
        if (fromDate != null && !fromDate.isBlank()) {
            wJql.append(" AND ").append(field).append(" >= \"").append(fromDate.trim()).append("\"");
        }
        if (toDate != null && !toDate.isBlank()) {
            wJql.append(" AND ").append(field).append(" <= \"").append(toDate.trim()).append("\"");
        }
        String workloadJqlString = wJql.toString();
        out.put("workloadJql", workloadJqlString);

        String currentUserDisplayName = null;
        try {
            currentUserDisplayName = jira.getCurrentUser().path("displayName").asText(null);
        } catch (Exception ignored) {
            // Best-effort only — workload comparison still works without the "you" highlight.
        }
        out.put("currentUser", currentUserDisplayName == null ? "" : currentUserDisplayName);

        try {
            JsonNode result = jira.searchIssues(workloadJqlString, 500);
            JsonNode issues = result.path("issues");

            Map<String, double[]> byAssignee = new LinkedHashMap<>(); // [points, ticketCount]
            for (JsonNode issue : issues) {
                JsonNode f = issue.path("fields");
                String assigneeName = f.path("assignee").path("displayName").asText("Unassigned");
                JsonNode spNode = f.path("customfield_10005");
                double points = spNode.isNumber() ? spNode.asDouble() : 0.0;

                double[] agg = byAssignee.computeIfAbsent(assigneeName, k -> new double[2]);
                agg[0] += points;
                agg[1] += 1;
            }

            List<Map.Entry<String, double[]>> ranked = new ArrayList<>(byAssignee.entrySet());
            ranked.sort(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> e.getValue()[0]).reversed());

            ArrayNode workloadArray = out.putArray("workload");
            for (Map.Entry<String, double[]> e : ranked) {
                ObjectNode w = workloadArray.addObject();
                w.put("assignee", e.getKey());
                w.put("storyPoints", e.getValue()[0]);
                w.put("ticketCount", (int) e.getValue()[1]);
                w.put("isCurrentUser", currentUserDisplayName != null && currentUserDisplayName.equals(e.getKey()));
            }
        } catch (Exception e) {
            // Don't fail the whole dashboard if the workload query has an issue
            // (e.g. no active sprint) — just omit the workload panel's data.
            out.putArray("workload");
        }
    }
}
