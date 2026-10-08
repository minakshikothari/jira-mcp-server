package io.github.minakshikothari.jiramcp.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.function.Consumer;

/**
 * Chat-tab "AI mode": turns free text into Jira actions using Claude's
 * tool-use (function calling), instead of CommandParser's fixed regex
 * phrasings. Claude is handed the same set of Jira operations as tool
 * schemas — the same shape as an MCP tool definition — decides which one to
 * call and with what arguments from whatever you typed, JiraToolExecutor
 * runs it against the real Jira REST API, and Claude is asked once more to
 * phrase the result back in plain language.
 *
 * This is the same idea as JiraMcpServerApp/JiraTools (an LLM calling
 * well-described tools against Jira through Claude Desktop) wired directly
 * into this web demo's own chat box instead — useful when you want a
 * self-contained "type anything, it acts" experience without opening Claude
 * Desktop in front of an audience.
 *
 * Keeps a running conversation (like Claude Desktop does) rather than
 * treating every message as a one-off: if a request is missing something
 * (no project key, no summary, ...), Claude asks a follow-up question
 * instead of calling a tool, and — because the conversation history is kept
 * across calls — your next message is understood as the answer to that
 * question, not a brand-new unrelated request.
 */
public class LlmCommandHandler {

    private static final String SYSTEM_PROMPT = """
            You are a Jira assistant for a self-hosted Jira Data Center instance. \
            You have no knowledge of any specific issue, project, or sprint except through \
            the tools you are given — always call a tool rather than guessing at Jira data. \
            If a request is missing something you need (e.g. no project key, no summary, no \
            issue key), don't guess — ask a short, specific follow-up question instead of \
            calling a tool, and wait for the answer in the next message. Use exactly one tool \
            call per user request once you have what you need. When a request says "the \
            current sprint" without naming it, use add_issue_to_sprint / remove_issue_from_sprint, \
            which resolve the active sprint for you automatically — never ask which sprint. \
            When a request just says "close" an issue with no exact status named, use \
            close_issue rather than transition_issue. Keep replies short — one or two sentences.""";

    // Cap so a long-running demo session's history can't grow unbounded; a
    // real chat wouldn't need this in one sitting, so a hard reset is fine.
    private static final int MAX_HISTORY_MESSAGES = 200;

    private final AnthropicClient anthropic;
    private final JiraToolExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();

    // The running conversation, in Anthropic Messages API shape. Lives for as
    // long as the server process does — this is a single-audience live demo,
    // not a multi-user product, so one shared thread is the right model.
    private ArrayNode conversationHistory = mapper.createArrayNode();

    public LlmCommandHandler(AnthropicClient anthropic, JiraToolExecutor executor) {
        this.anthropic = anthropic;
        this.executor = executor;
    }

    /** Starts a fresh conversation — e.g. before a new demo run. */
    public void resetConversation() {
        conversationHistory = mapper.createArrayNode();
    }

    public ObjectNode handle(String rawMessage) {
        ObjectNode out = mapper.createObjectNode();

        if (rawMessage == null || rawMessage.isBlank()) {
            out.put("ok", false);
            out.put("message", "Type a command, or click one of the examples above.");
            return out;
        }

        int historyBeforeThisTurn = conversationHistory.size();
        try {
            ArrayNode tools = buildToolSchemas();

            conversationHistory.addObject().put("role", "user").put("content", rawMessage);

            JsonNode first = anthropic.createMessage(SYSTEM_PROMPT, conversationHistory, tools);
            JsonNode content = first.path("content");

            JsonNode toolUseBlock = null;
            StringBuilder claudeText = new StringBuilder();
            for (JsonNode block : content) {
                if ("tool_use".equals(block.path("type").asText())) {
                    toolUseBlock = block;
                    break;
                }
                if ("text".equals(block.path("type").asText())) {
                    if (claudeText.length() > 0) claudeText.append(" ");
                    claudeText.append(block.path("text").asText(""));
                }
            }

            if (toolUseBlock == null) {
                // Claude answered directly without calling a tool — either a
                // follow-up question because something was missing, or small
                // talk / "help". Either way, remember it said this so the
                // next message is read as a continuation of this thread.
                conversationHistory.addObject().put("role", "assistant").set("content", content);
                trimHistory();

                out.put("ok", true);
                out.put("message", claudeText.length() > 0 ? claudeText.toString()
                        : "I'm not sure how to help with that — try naming a specific issue, project, or sprint.");
                return out;
            }

            String toolName = toolUseBlock.path("name").asText();
            JsonNode toolInput = toolUseBlock.path("input");
            String toolUseId = toolUseBlock.path("id").asText();

            ObjectNode execResult = executor.execute(toolName, toolInput);
            boolean toolOk = execResult.path("ok").asBoolean(true);

            if (execResult.has("issues")) out.set("issues", execResult.get("issues"));
            if (execResult.has("issueDetail")) out.set("issueDetail", execResult.get("issueDetail"));
            out.put("toolName", toolName);
            out.set("toolArgs", toolInput);

            // Second turn: hand the tool result back so Claude can phrase the
            // final reply itself, the way a real assistant would.
            conversationHistory.addObject().put("role", "assistant").set("content", content);

            ObjectNode toolResultBlock = mapper.createObjectNode();
            toolResultBlock.put("type", "tool_result");
            toolResultBlock.put("tool_use_id", toolUseId);
            toolResultBlock.put("content", execResult.toString());
            if (!toolOk) toolResultBlock.put("is_error", true);

            ArrayNode toolResultContent = mapper.createArrayNode();
            toolResultContent.add(toolResultBlock);
            conversationHistory.addObject().put("role", "user").set("content", toolResultContent);

            JsonNode second = anthropic.createMessage(SYSTEM_PROMPT, conversationHistory, tools);
            StringBuilder finalText = new StringBuilder();
            for (JsonNode block : second.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    if (finalText.length() > 0) finalText.append(" ");
                    finalText.append(block.path("text").asText(""));
                }
            }
            conversationHistory.addObject().put("role", "assistant").set("content", second.path("content"));
            trimHistory();

            out.put("ok", toolOk);
            out.put("message", finalText.length() > 0 ? finalText.toString()
                    : execResult.path("message").asText(toolOk ? "Done." : "Something went wrong."));

        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // Roll back whatever partial turn we'd started so the history
            // stays a valid, complete conversation for the next message.
            while (conversationHistory.size() > historyBeforeThisTurn) {
                conversationHistory.remove(conversationHistory.size() - 1);
            }
            out.put("ok", false);
            out.put("message", "Error: " + e.getMessage());
        }

        return out;
    }

    private void trimHistory() {
        // A hard reset rather than a partial trim: dropping only the oldest
        // message(s) risks splitting a tool_use/tool_result pair, which the
        // API rejects. This cap is generous enough that a live demo will
        // never actually hit it.
        if (conversationHistory.size() > MAX_HISTORY_MESSAGES) {
            resetConversation();
        }
    }

    private ArrayNode buildToolSchemas() {
        ArrayNode tools = mapper.createArrayNode();

        tools.add(tool("search_issues",
                "Search Jira issues with a JQL query. Use this for any 'show me issues where...' or "
                        + "'find tickets...' request — construct valid JQL yourself from what the user asked. "
                        + "If the user only vaguely describes what an issue is about (no clear field to filter "
                        + "on — a fuzzy recollection like 'something about a login timeout'), use the text ~ "
                        + "\"...\" operator to search summaries, descriptions and comments instead of guessing "
                        + "at exact field values.",
                props -> {
                    prop(props, "jql", "string", "A valid JQL query, e.g. project = PROJ AND assignee = currentUser(), "
                            + "or text ~ \"login timeout\" for a fuzzy/free-text search");
                    prop(props, "maxResults", "integer", "Max results to return, default 20");
                },
                "jql"));

        tools.add(tool("get_issue",
                "Fetch full details for a single Jira issue by its key.",
                props -> prop(props, "issueKey", "string", "Issue key, e.g. PROJ-123"),
                "issueKey"));

        tools.add(tool("create_issue",
                "Create a new Jira issue.",
                props -> {
                    prop(props, "projectKey", "string", "Project key, e.g. PROJ");
                    prop(props, "issueType", "string", "Task, Story, or Defect");
                    prop(props, "summary", "string", "Short issue title");
                    prop(props, "description", "string", "Longer description (optional)");
                },
                "projectKey", "issueType", "summary"));

        tools.add(tool("add_comment",
                "Add a comment to an existing Jira issue.",
                props -> {
                    prop(props, "issueKey", "string", "Issue key, e.g. PROJ-123");
                    prop(props, "comment", "string", "Comment text");
                },
                "issueKey", "comment"));

        tools.add(tool("transition_issue",
                "Move an issue to a specific, exactly-named workflow status (e.g. 'In Review', "
                        + "'Business Review'). Only use this when the user names a specific status; "
                        + "use close_issue instead if they just say 'close'.",
                props -> {
                    prop(props, "issueKey", "string", "Issue key, e.g. PROJ-123");
                    prop(props, "transitionName", "string", "Exact target status name");
                },
                "issueKey", "transitionName"));

        tools.add(tool("close_issue",
                "Close/resolve an issue, automatically picking whichever available workflow transition "
                        + "looks like Done/Closed/Resolved. Use this whenever the user just says 'close X' "
                        + "without naming an exact status.",
                props -> prop(props, "issueKey", "string", "Issue key, e.g. PROJ-123"),
                "issueKey"));

        tools.add(tool("add_issue_to_sprint",
                "Add an issue to its project's currently active sprint. Use this for 'add X to the "
                        + "current sprint' style requests — the active sprint is resolved automatically, "
                        + "you never need to name or guess the sprint.",
                props -> prop(props, "issueKey", "string", "Issue key, e.g. PROJ-123"),
                "issueKey"));

        tools.add(tool("remove_issue_from_sprint",
                "Remove an issue from its current sprint, moving it back to the backlog.",
                props -> prop(props, "issueKey", "string", "Issue key, e.g. PROJ-123"),
                "issueKey"));

        return tools;
    }

    private ObjectNode tool(String name, String description, Consumer<ObjectNode> properties, String... required) {
        ObjectNode t = mapper.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        ObjectNode schema = t.putObject("input_schema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        properties.accept(props);
        ArrayNode req = schema.putArray("required");
        for (String r : required) req.add(r);
        return t;
    }

    private void prop(ObjectNode props, String name, String type, String description) {
        props.putObject(name).put("type", type).put("description", description);
    }
}
