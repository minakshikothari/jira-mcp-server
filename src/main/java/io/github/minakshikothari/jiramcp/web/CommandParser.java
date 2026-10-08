package io.github.minakshikothari.jiramcp.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a free-text command typed into the demo UI into a structured intent.
 *
 * This is deliberately simple (regex pattern matching, no external NLU/LLM
 * dependency) so the whole POC runs offline with nothing but the JDK and the
 * Jira REST API — good enough to recognize the handful of phrasings you'd
 * actually use in a live demo. Extending this to a real LLM-based parser
 * later is a drop-in swap: CommandHandler only depends on ParsedCommand.
 */
public class CommandParser {

    private static final String KEY = "[A-Z][A-Z0-9]+-\\d+";

    private static final Pattern CREATE = Pattern.compile(
            "create\\s+(?:a\\s+|an\\s+)?(bug|task|story|epic)?\\s*(?:in|for)\\s+([A-Z][A-Z0-9]+)\\s*[:\\-]\\s*(.+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COMMENT = Pattern.compile(
            "(?:comment on|add comment to|add a comment to)\\s+(" + KEY + ")\\s*[:\\-]\\s*(.+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TRANSITION = Pattern.compile(
            "(?:move|transition|set)\\s+(" + KEY + ")\\s+to\\s+(.+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern GET = Pattern.compile(
            "(?:get|show|open|details on|details for)\\s+(?:issue\\s+)?(" + KEY + ")",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BARE_KEY = Pattern.compile("^(" + KEY + ")$", Pattern.CASE_INSENSITIVE);

    private static final Pattern PROJECT_ISSUES = Pattern.compile(
            "(?:show\\s+)?(open\\s+)?issues?\\s+in\\s+([A-Z][A-Z0-9]+)",
            Pattern.CASE_INSENSITIVE);

    // Matches an issue key wherever it appears in the sentence — used for the
    // more loosely-phrased commands below ("can you PROJ-123 add this to
    // the current sprint?") where the key isn't at a fixed position.
    private static final Pattern ANY_KEY = Pattern.compile("(" + KEY + ")", Pattern.CASE_INSENSITIVE);

    // Rough "does this look like actual JQL" check, used to decide whether the
    // unmatched-input fallback is safe to send to Jira as a query — plain
    // English ("create a task") isn't valid JQL and just trips over reserved
    // words like CREATE/AND/OR, so only text with real JQL syntax gets sent.
    private static final Pattern JQL_HINT = Pattern.compile(
            "=|~|!=|\\bAND\\b|\\bOR\\b|\\bORDER\\s+BY\\b|\\bIN\\s*\\(", Pattern.CASE_INSENSITIVE);

    public record ParsedCommand(String intent, Map<String, String> args, String rawInput) {
    }

    public static ParsedCommand parse(String input) {
        String s = input == null ? "" : input.trim();
        String lower = s.toLowerCase();

        if (lower.isEmpty()) {
            return command("empty", Map.of(), s);
        }
        if (lower.equals("help") || lower.equals("?") || lower.startsWith("what can you")) {
            return command("help", Map.of(), s);
        }
        if (lower.startsWith("jql:")) {
            return command("search", Map.of("jql", s.substring(4).trim()), s);
        }

        // Sprint membership: "add PROJ-123 to the current sprint", "put
        // PROJ-123 in sprint", "remove PROJ-123 from sprint" — key can
        // appear anywhere in the sentence, so this checks keywords + a found key
        // rather than requiring one fixed word order.
        if (lower.contains("sprint")) {
            Matcher keyM = ANY_KEY.matcher(s);
            if (keyM.find()) {
                String issueKey = keyM.group(1).toUpperCase();
                boolean removing = lower.contains("remove") || lower.contains("take out") || lower.contains("out of");
                return command(removing ? "removeFromSprint" : "addToSprint",
                        Map.of("issueKey", issueKey), s);
            }
        }

        // Closing an issue: "close PROJ-123", "close PROJ-123: fixed".
        if (lower.contains("close")) {
            Matcher keyM = ANY_KEY.matcher(s);
            if (keyM.find()) {
                return command("close", Map.of("issueKey", keyM.group(1).toUpperCase()), s);
            }
        }

        Matcher createM = CREATE.matcher(s);
        if (createM.matches()) {
            String issueType = createM.group(1) != null ? capitalize(createM.group(1)) : "Task";
            String project = createM.group(2).toUpperCase();
            String rest = createM.group(3).trim();

            String summary = rest;
            String description = "";
            int idx = rest.toLowerCase().indexOf("with description");
            if (idx >= 0) {
                summary = rest.substring(0, idx).trim();
                description = rest.substring(idx + "with description".length())
                        .replaceFirst("^[:\\-]\\s*", "").trim();
            }

            Map<String, String> args = new LinkedHashMap<>();
            args.put("projectKey", project);
            args.put("issueType", issueType);
            args.put("summary", summary);
            args.put("description", description);
            return command("create", args, s);
        }

        // "create ..." that didn't match the full pattern above (missing a
        // project and/or a "summary" after ":") — give a specific nudge
        // instead of falling through to raw JQL, where "create" is a reserved
        // word and always errors.
        if (lower.startsWith("create")) {
            return command("createHelp", Map.of(), s);
        }

        Matcher commentM = COMMENT.matcher(s);
        if (commentM.matches()) {
            Map<String, String> args = new LinkedHashMap<>();
            args.put("issueKey", commentM.group(1).toUpperCase());
            args.put("comment", commentM.group(2).trim());
            return command("comment", args, s);
        }

        Matcher transitionM = TRANSITION.matcher(s);
        if (transitionM.matches()) {
            Map<String, String> args = new LinkedHashMap<>();
            args.put("issueKey", transitionM.group(1).toUpperCase());
            args.put("transitionName", transitionM.group(2).trim());
            return command("transition", args, s);
        }

        if (lower.contains("my issue") || lower.contains("assigned to me")) {
            return command("search", Map.of("jql", "assignee = currentUser() ORDER BY updated DESC"), s);
        }

        Matcher projectM = PROJECT_ISSUES.matcher(s);
        if (projectM.find()) {
            boolean openOnly = projectM.group(1) != null;
            String project = projectM.group(2).toUpperCase();
            String jql = openOnly
                    ? "project = " + project + " AND resolution = Unresolved ORDER BY updated DESC"
                    : "project = " + project + " ORDER BY updated DESC";
            return command("search", Map.of("jql", jql), s);
        }

        Matcher getM = GET.matcher(s);
        if (getM.find()) {
            return command("get", Map.of("issueKey", getM.group(1).toUpperCase()), s);
        }

        Matcher bareM = BARE_KEY.matcher(s);
        if (bareM.matches()) {
            return command("get", Map.of("issueKey", bareM.group(1).toUpperCase()), s);
        }

        // Fallback: if it looks like real JQL syntax, run it as-is. Otherwise
        // treat the whole message as a free-text search across issue
        // summaries, descriptions AND comments (Jira's "text ~" operator) —
        // this is what makes "I vaguely remember something about a login
        // timeout" style input actually find something, instead of just
        // erroring because it isn't a recognized command or valid JQL.
        if (JQL_HINT.matcher(s).find()) {
            return command("search", Map.of("jql", s), s);
        }
        String escapedPhrase = s.replace("\"", "'");
        String textJql = "text ~ \"" + escapedPhrase + "\" ORDER BY updated DESC";
        return command("search", Map.of("jql", textJql), s);
    }

    private static ParsedCommand command(String intent, Map<String, String> args, String raw) {
        return new ParsedCommand(intent, args, raw);
    }

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }
}
