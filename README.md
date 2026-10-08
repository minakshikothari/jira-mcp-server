# Jira MCP Server — Proof of Concept

A working demo of exposing Jira as an **MCP (Model Context Protocol) server**
instead of a conventional microservice — built to show what "IRIS MCP",
"NGA MCP", "travel MCP" etc. actually mean in practice, and why the naming
shift is happening.

## The one-line pitch

> MCP doesn't replace microservices. It's a **standard interface layer on
> top of them** so any AI agent — Claude, an internal copilot, a chat
> assistant — can call your existing systems without you writing a custom
> integration for every agent that wants access.

A microservice exposes a REST/gRPC API meant for other services and
developers to integrate against by hand. An MCP server exposes the *same*
kind of capability, but described in a way an LLM can read and call
directly at runtime — no bespoke client code, no per-integration glue.

```
Before (microservice only)              After (+ MCP layer)
┌─────────────┐                         ┌─────────────┐
│  Jira REST  │◄── custom integration    │  Jira REST  │
│    API      │    code, per consumer    │    API      │
└─────────────┘                         └──────┬──────┘
                                                │
                                         ┌──────▼──────┐
                                         │  Jira MCP   │◄── any MCP client:
                                         │   Server    │    Claude, Claude Code,
                                         └─────────────┘    internal copilot, ...
```

## What this POC does

Runs a small Java server (`JiraMcpServerApp`) that exposes five Jira
operations as MCP **tools** — self-describing functions an AI agent can
discover and call:

| Tool | What it does |
|---|---|
| `search_issues` | Run a JQL query, get back key/status/assignee/priority |
| `get_issue` | Full detail on one issue |
| `create_issue` | File a new issue in a project |
| `add_comment` | Comment on an issue |
| `transition_issue` | Move an issue through its workflow by status name |

There are **two ways to run it**, both hitting the same live Jira instance
through the same `JiraClient`:

1. **As an MCP server** (`JiraMcpServerApp`) — talks JSON-RPC over stdio to
   an MCP client like Claude Desktop. No UI at all; the client provides it.
2. **As a standalone browser demo** (`JiraWebDemoApp`) — a small local web
   app with two tabs, for demoing to people who aren't going to open Claude
   Desktop. Good for a management walkthrough on a shared screen:
   - **Chat** — type a command, see it become one of the 5 tool calls above.
   - **Dashboard** — pick a project, optionally the current sprint and/or a
     date range, and see a live status breakdown + ticket table.

## Project layout

```
jira-mcp-server/
├── pom.xml
├── README.md
└── src/main/
    ├── java/com/your organization/jiramcp/
    │   ├── JiraMcpServerApp.java   # MCP entry point (stdio, for Claude Desktop)
    │   ├── JiraClient.java         # plain HTTP client for Jira REST API v2 (Data Center)
    │   ├── JiraTools.java          # the 5 MCP tool definitions + handlers
    │   └── web/
    │       ├── JiraWebDemoApp.java     # standalone browser demo entry point (Chat + Dashboard)
    │       ├── JiraToolExecutor.java   # the actual Jira actions — shared by both Chat modes below
    │       ├── CommandParser.java      # fixed-phrase regex parser (Chat tab, "AI mode" off)
    │       ├── CommandHandler.java     # runs a parsed command through JiraToolExecutor
    │       ├── AnthropicClient.java    # thin Claude Messages API client (tool-use only)
    │       ├── LlmCommandHandler.java  # free text -> Claude picks a tool -> JiraToolExecutor ("AI mode" on)
    │       └── DashboardHandler.java   # builds JQL + aggregates results for the Dashboard tab
    └── resources/web/
        ├── index.html              # both tabs' markup
        ├── style.css
        └── app.js
```

## Auth: Personal Access Token (Data Center), not Cloud

`jira.example.com` is a self-hosted Jira Server/Data Center instance, not
Jira Cloud, so this uses **Personal Access Token auth as a Bearer header**
against the `/rest/api/2/...` endpoints (Data Center's v2, not Cloud's v3),
with plain-text issue descriptions and comments instead of Cloud's ADF JSON
format. Create a token under your Jira profile picture -> Personal Access
Tokens. If you ever point this at a `*.atlassian.net` Cloud site instead,
you'd need to swap back to `Basic base64(email:api_token)` against
`/rest/api/3` with ADF-formatted bodies.

## Building

```bash
mvn clean package
```

This produces one shaded (all-dependencies-included) jar at
`target/jira-mcp-server.jar`, used by both run modes below.

## Running it as an MCP server (Claude Desktop)

```bash
JIRA_BASE_URL=https://jira.example.com \
JIRA_ACCESS_TOKEN=<Personal Access Token from your Jira profile> \
java -jar target/jira-mcp-server.jar
```

The process then listens on stdio for MCP JSON-RPC messages — it's designed
to be launched by an MCP client, not called directly. To try that, add to
Claude Desktop's config (Settings → Developer → Edit Config):

```json
{
  "mcpServers": {
    "jira": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/target/jira-mcp-server.jar"],
      "env": {
        "JIRA_BASE_URL": "https://jira.example.com",
        "JIRA_ACCESS_TOKEN": "<token>"
      }
    }
  }
}
```

Restart Claude Desktop and the five tools appear automatically — that
"zero integration code on the client side" moment is worth calling out
live.

## Running the web demo UI (for a management walkthrough)

```bash
JIRA_BASE_URL=https://jira.example.com \
JIRA_ACCESS_TOKEN=<Personal Access Token from your Jira profile> \
ANTHROPIC_API_KEY=<optional, turns on AI mode> \
java -cp target/jira-mcp-server.jar io.github.minakshikothari.jiramcp.web.JiraWebDemoApp
```

Then open **http://localhost:4321** in a browser. (Optional: set `WEB_PORT`
to use a different port.)

This is a small chat page — type a command, it turns into one of the Jira
tool calls above, and you see the real Jira response. Each reply has a
collapsible **"Show MCP tool call"** panel underneath it, so the audience
can see exactly which tool fired and with what arguments — that's the part
that makes "it's MCP under the hood" concrete rather than a claim you're
making in the abstract.

### Two ways to drive the Chat tab

- **AI mode off (default)** — `CommandParser` matches a handful of fixed
  phrasings with regex, no external calls beyond Jira itself. Deterministic
  and works with zero extra setup, but only understands the phrasings below.
- **AI mode on** (toggle above the chat, enabled once `ANTHROPIC_API_KEY` is
  set) — Claude reads the same tool definitions as an MCP server would
  expose, decides which one fits whatever you typed, fills in the arguments,
  and phrases the final reply itself. This is the "real chatbot" experience:
  natural phrasing, no fixed word order, no Claude Desktop needing to be open
  on screen during the demo. Both modes call the exact same Jira actions in
  `JiraToolExecutor` — only "who decides which tool to call" changes, which
  is worth calling out live as the actual point of MCP: the tool surface is
  the stable, reusable part.

Try commands like:
- `show my issues`
- `issues in PROJ`
- `PROJ-123` (bare key also works, or `get issue PROJ-123`)
- `create a bug in PROJ: login fails on expired session with description steps to reproduce...`
- `comment on PROJ-123: verified in staging`
- `move PROJ-123 to Done`
- `close PROJ-123`
- `add PROJ-123 to the current sprint` / `remove PROJ-123 from the sprint`
- `jql: project = PROJ AND status = "In Review"` (raw JQL passthrough for power users)

With AI mode on, phrasing doesn't matter — "can you close out PROJ-123"
or "put PROJ-123 into this sprint please" both work.

The command parsing is intentionally simple regex matching (no external
NLU/LLM call) so the whole thing runs offline with nothing but the JDK and
your Jira instance — good enough for a live demo, and a clean seam if you
later want to swap in real LLM-based parsing.

### The Dashboard tab

Switch to **Dashboard** and you get:

- **Project** — any project key (defaults to `PROJ`).
- **Current sprint only** — adds `sprint in openSprints()` to the query.
  Only works for projects on a Scrum board with an active sprint; if the
  project doesn't have one, the dashboard shows a clear message telling you
  to uncheck it rather than a raw Jira error.
- **Date field + From/To** — filters by `Created`, `Updated`, or `Resolved`
  date, so you can look at "everything closed last month" as easily as
  "what's in the current sprint right now." Both filters combine (e.g.
  current sprint AND created after a date).

What you get back:
- 4 KPI tiles: **Total**, **To Do**, **In Progress**, **Done** — bucketed
  off Jira's own status-category key, not literal status names, so it works
  correctly even with fully custom workflows (this instance's real statuses
  include things like "Rootcause Analysis" and "Certification," which still
  bucket correctly into one of the three categories).
- A ranked bar chart of the top 8 actual status names (the rest fold into
  "Other"), for a truer picture than the 3-bucket KPIs alone.
- A full ticket table (key, status, summary, assignee, priority, updated),
  each key linking straight to the real issue in Jira.
- A "Show JQL used" toggle under the ticket count, for the same reason the
  Chat tab shows its tool calls — so the query behind the numbers isn't a
  black box.

## Extending this for the real pitch

For an actual management proposal, the strongest next steps are usually:
1. **Pick a real internal consumer** — e.g. "our support copilot can now
   triage and file Jira bugs without a dedicated integration."
2. **Add auth/scoping** — this POC uses one personal access token; a
   production version needs per-user OAuth or a scoped bot account so the
   agent can't do more than the calling user could.
3. **Point at more than one system** — the payoff compounds once Jira,
   Confluence, and an internal service all speak MCP: one agent, one
   protocol, many tools.
