package io.github.minakshikothari.jiramcp.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Minimal client for the Claude (Anthropic) Messages API, used only for its
 * tool-use ("function calling") feature.
 *
 * This is the "brain" that LlmCommandHandler plugs in front of Jira: it
 * doesn't know anything about Jira itself. It's handed a system prompt, the
 * running conversation, and a list of tool schemas (name, description, JSON
 * input shape — the exact same idea as an MCP tool definition) and returns
 * whichever tool Claude decided to call, with what arguments, or a plain
 * text answer if no tool was needed.
 *
 * Auth: an Anthropic API key (env var ANTHROPIC_API_KEY), separate from the
 * Jira Personal Access Token — this is what your own management demo talks
 * to instead of requiring Claude Desktop to be open on screen.
 */
public class AnthropicClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    // Model ids change over time and are environment-specific, so make this
    // configurable and verify the current id at https://docs.claude.com/en/docs/about-claude/models
    private static final String MODEL =
            System.getenv().getOrDefault("ANTHROPIC_MODEL", "claude-sonnet-4-5");

    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public AnthropicClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * One call to POST /v1/messages. `messages` and `tools` are raw,
     * Anthropic-API-shaped JSON arrays built by the caller. Returns the full
     * parsed response body (content blocks, stop_reason, etc.).
     */
    public JsonNode createMessage(String systemPrompt, ArrayNode messages, ArrayNode tools) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        body.put("max_tokens", 1024);
        body.put("system", systemPrompt);
        body.set("messages", messages);
        if (tools != null) {
            body.set("tools", tools);
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        if (resp.statusCode() >= 300) {
            throw new RuntimeException("Anthropic API error " + resp.statusCode() + ": " + resp.body());
        }
        return mapper.readTree(resp.body());
    }
}
