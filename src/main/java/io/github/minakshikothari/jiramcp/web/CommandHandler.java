package io.github.minakshikothari.jiramcp.web;

import io.github.minakshikothari.jiramcp.JiraClient;
import io.github.minakshikothari.jiramcp.web.CommandParser.ParsedCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * The original, deterministic way to drive the Chat tab: CommandParser
 * recognizes a handful of fixed phrasings via regex and maps each straight to
 * one of the shared tools in JiraToolExecutor — no LLM call, works fully
 * offline. Kept as a fallback alongside LlmCommandHandler (the free-text,
 * Claude-driven version) for when ANTHROPIC_API_KEY isn't set.
 */
public class CommandHandler {

    private final JiraToolExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();

    public CommandHandler(JiraClient jira, String baseUrl) {
        this.executor = new JiraToolExecutor(jira, baseUrl);
    }

    public ObjectNode handle(String rawMessage) {
        ParsedCommand cmd = CommandParser.parse(rawMessage);
        ObjectNode out;

        try {
            out = switch (cmd.intent()) {
                case "empty" -> errorNode("Type a command, or click one of the examples above.");
                case "help" -> helpNode();
                case "createHelp" -> errorNode("To create an issue I need a project and a summary. Try: "
                        + "\"create a task in PROJ: <summary>\" — also works with bug/story/epic, and you "
                        + "can add \"with description ...\" at the end for more detail. Or turn on AI mode "
                        + "above and just describe what you want created.");
                case "search" -> executor.execute("search_issues", toArgs(cmd.args()));
                case "get" -> executor.execute("get_issue", toArgs(cmd.args()));
                case "create" -> executor.execute("create_issue", toArgs(cmd.args()));
                case "comment" -> executor.execute("add_comment", toArgs(cmd.args()));
                case "transition" -> executor.execute("transition_issue", toArgs(cmd.args()));
                case "close" -> executor.execute("close_issue", toArgs(cmd.args()));
                case "addToSprint" -> executor.execute("add_issue_to_sprint", toArgs(cmd.args()));
                case "removeFromSprint" -> executor.execute("remove_issue_from_sprint", toArgs(cmd.args()));
                default -> errorNode("I didn't recognize that. Type \"help\" to see example commands, "
                        + "or switch on AI mode above to type more naturally.");
            };
        } catch (Exception e) {
            out = errorNode("Jira error: " + e.getMessage());
        }
        return out;
    }

    private ObjectNode toArgs(Map<String, String> args) {
        ObjectNode node = mapper.createObjectNode();
        args.forEach((k, v) -> {
            if (v != null) node.put(k, v);
        });
        return node;
    }

    private ObjectNode errorNode(String message) {
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", false);
        out.put("message", message);
        return out;
    }

    private ObjectNode helpNode() {
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        out.put("message", String.join("\n",
                "Here's what I can do — try any of these:",
                "• show my issues",
                "• issues in PROJ",
                "• PROJ-123  (or: get issue PROJ-123)",
                "• create a bug in PROJ: login fails on expired session with description steps to reproduce...",
                "• comment on PROJ-123: verified in staging",
                "• move PROJ-123 to Done",
                "• close PROJ-123",
                "• add PROJ-123 to the current sprint",
                "• remove PROJ-123 from the sprint",
                "• jql: project = PROJ AND status = \"In Review\"",
                "",
                "Anything else you type is searched as free text across issue summaries, "
                        + "descriptions, and comments — handy if you only half-remember what an issue "
                        + "was about, e.g. \"something about login timeout\".",
                "",
                "Turn on \"AI mode\" above to type this however you naturally would instead."
        ));
        return out;
    }
}
