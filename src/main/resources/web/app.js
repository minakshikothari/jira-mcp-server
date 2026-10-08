// ---------------- Shared helpers ----------------

function escapeHtml(str) {
  const div = document.createElement("div");
  div.textContent = str == null ? "" : str;
  return div.innerHTML;
}

// ---------------- Tabs ----------------

const tabButtons = document.querySelectorAll(".tab-btn");
const tabPanels = document.querySelectorAll(".tab-panel");

tabButtons.forEach((btn) => {
  btn.addEventListener("click", () => {
    tabButtons.forEach((b) => b.classList.remove("active"));
    tabPanels.forEach((p) => p.classList.remove("active"));
    btn.classList.add("active");
    document.getElementById("tab-" + btn.dataset.tab).classList.add("active");
  });
});

// ---------------- Chat tab ----------------

const chat = document.getElementById("chat");
const composer = document.getElementById("composer");
const input = document.getElementById("input");
const sendBtn = document.getElementById("sendBtn");
const examples = document.getElementById("examples");
const statusDot = document.getElementById("statusDot");
const statusText = document.getElementById("statusText");
const aiModeToggle = document.getElementById("aiModeToggle");
const aiModeToggleLabel = document.getElementById("aiModeToggleLabel");
const aiModeHint = document.getElementById("aiModeHint");
const brainChip = document.getElementById("brainChip");
const resetChatBtn = document.getElementById("resetChatBtn");

let aiAvailable = false;

async function loadConfig() {
  try {
    const res = await fetch("/api/config", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
    const data = await res.json();
    aiAvailable = !!data.aiAvailable;
  } catch (err) {
    aiAvailable = false;
  }

  aiModeToggle.disabled = !aiAvailable;
  if (aiAvailable) {
    aiModeToggleLabel.title = "";
    aiModeHint.textContent = "";
  } else {
    aiModeToggleLabel.title = "Set ANTHROPIC_API_KEY and restart the server to enable AI mode.";
    aiModeHint.textContent = "(not configured)";
  }
}

aiModeToggle.addEventListener("change", () => {
  brainChip.textContent = aiModeToggle.checked ? "Claude decides which tool to call" : "MCP tool selected";
});

resetChatBtn.addEventListener("click", async () => {
  try {
    await fetch("/api/reset", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
  } catch (err) {
    // Best-effort — if the server can't be reached, the chat log below is
    // just as informative about what happened.
  }
  chat.innerHTML = "";
});

loadConfig();

function scrollToBottom() {
  chat.scrollTop = chat.scrollHeight;
}

function addUserMessage(text) {
  const msg = document.createElement("div");
  msg.className = "msg user";
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  bubble.textContent = text;
  msg.appendChild(bubble);
  chat.appendChild(msg);
  scrollToBottom();
}

function renderIssueCard(issue) {
  const card = document.createElement("div");
  card.className = "issue-card";
  card.innerHTML = `
    <div class="issue-top">
      <span class="issue-key"><a href="${issue.url}" target="_blank" rel="noopener">${issue.key}</a></span>
      <span class="status-badge">${escapeHtml(issue.status || "")}</span>
    </div>
    <div class="issue-summary">${escapeHtml(issue.summary || "")}</div>
    <div class="issue-meta">${escapeHtml(issue.assignee || "Unassigned")}${issue.priority ? " &middot; " + escapeHtml(issue.priority) : ""}</div>
  `;
  return card;
}

function renderIssueDetail(detail) {
  const card = document.createElement("div");
  card.className = "issue-card";
  card.innerHTML = `
    <div class="issue-top">
      <span class="issue-key"><a href="${detail.url}" target="_blank" rel="noopener">${detail.key}</a></span>
      <span class="status-badge">${escapeHtml(detail.status || "")}</span>
    </div>
    <div class="issue-summary">${escapeHtml(detail.summary || "")}</div>
    <div class="issue-meta">
      ${detail.type ? escapeHtml(detail.type) + " &middot; " : ""}
      ${detail.priority ? escapeHtml(detail.priority) + " &middot; " : ""}
      ${escapeHtml(detail.assignee || "Unassigned")}
      ${detail.updated ? " &middot; updated " + escapeHtml(detail.updated) : ""}
    </div>
  `;
  return card;
}

function addAssistantMessage(data) {
  const msg = document.createElement("div");
  msg.className = "msg assistant" + (data.ok === false ? " error" : "");

  const bubble = document.createElement("div");
  bubble.className = "bubble";

  const p = document.createElement("p");
  p.textContent = data.message || "";
  bubble.appendChild(p);

  if (Array.isArray(data.issues)) {
    data.issues.forEach((issue) => bubble.appendChild(renderIssueCard(issue)));
  }
  if (data.issueDetail) {
    bubble.appendChild(renderIssueDetail(data.issueDetail));
  }

  if (data.toolName) {
    const details = document.createElement("details");
    details.className = "tool-call";
    const summary = document.createElement("summary");
    summary.textContent = "Show MCP tool call";
    const pre = document.createElement("pre");
    pre.textContent = data.toolName + "(" + JSON.stringify(data.toolArgs, null, 2) + ")";
    details.appendChild(summary);
    details.appendChild(pre);
    bubble.appendChild(details);
  }

  msg.appendChild(bubble);
  chat.appendChild(msg);
  scrollToBottom();
}

function setBusy(isBusy, errored) {
  sendBtn.disabled = isBusy;
  input.disabled = isBusy;
  statusDot.className = "status-dot" + (isBusy ? " busy" : errored ? " error" : "");
  statusText.textContent = isBusy ? "Working..." : errored ? "Last request failed" : "Ready";
}

async function sendCommand(text) {
  addUserMessage(text);
  setBusy(true, false);
  try {
    const res = await fetch("/api/command", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message: text, aiMode: aiModeToggle.checked }),
    });
    const data = await res.json();
    addAssistantMessage(data);
    setBusy(false, data.ok === false);
  } catch (err) {
    addAssistantMessage({ ok: false, message: "Could not reach the local server: " + err.message });
    setBusy(false, true);
  }
}

composer.addEventListener("submit", (e) => {
  e.preventDefault();
  const text = input.value.trim();
  if (!text) return;
  input.value = "";
  sendCommand(text);
});

examples.addEventListener("click", (e) => {
  const btn = e.target.closest(".chip");
  if (!btn) return;
  sendCommand(btn.dataset.cmd);
});

// ---------------- Dashboard tab ----------------

const dashboardFilters = document.getElementById("dashboardFilters");
const applyBtn = document.getElementById("applyBtn");
const dashboardError = document.getElementById("dashboardError");
const dashboardContent = document.getElementById("dashboardContent");
const dashboardEmpty = document.getElementById("dashboardEmpty");
const kpiRow = document.getElementById("kpiRow");
const statusBarList = document.getElementById("statusBarList");
const workloadBarList = document.getElementById("workloadBarList");
const workloadJql = document.getElementById("workloadJql");
const ticketTableBody = document.getElementById("ticketTableBody");
const ticketCount = document.getElementById("ticketCount");
const dashboardJql = document.getElementById("dashboardJql");
const onlyMyTicketsCheckbox = document.getElementById("onlyMyTickets");
const assigneeField = document.getElementById("assigneeField");
const typeChips = document.getElementById("typeChips");
const filterIndicator = document.getElementById("filterIndicator");
const filterIndicatorLabel = document.getElementById("filterIndicatorLabel");
const clearFilterBtn = document.getElementById("clearFilterBtn");

const KPI_DEFS = [
  { key: "Total", label: "Total", dot: null },
  { key: "To Do", label: "To Do", dot: "var(--viz-todo)" },
  { key: "In Progress", label: "In Progress", dot: "var(--viz-inprogress)" },
  { key: "Done", label: "Done", dot: "var(--viz-done)" },
];

let activeIssueType = "All";
let lastIssues = [];
// activeTableFilter: null (show all), {type:"category", value:"Done"} or {type:"status", value:"In Review"}
let activeTableFilter = null;

function statusBadgeClass(category) {
  if (category === "Done") return "done";
  if (category === "In Progress") return "inprogress";
  return "todo";
}

onlyMyTicketsCheckbox.addEventListener("change", () => {
  assigneeField.hidden = onlyMyTicketsCheckbox.checked;
});

typeChips.addEventListener("click", (e) => {
  const btn = e.target.closest(".type-chip");
  if (!btn) return;
  activeIssueType = btn.dataset.type;
  [...typeChips.children].forEach((c) => c.classList.remove("active"));
  btn.classList.add("active");
  loadDashboard();
});

function renderKpis(categoryCounts, total) {
  const countsByName = {};
  categoryCounts.forEach((c) => (countsByName[c.name] = c.count));

  kpiRow.innerHTML = "";
  KPI_DEFS.forEach((def) => {
    const value = def.key === "Total" ? total : (countsByName[def.key] || 0);
    const tile = document.createElement("div");
    tile.className = "kpi-tile";
    tile.dataset.key = def.key;
    const isActive = (def.key === "Total" && !activeTableFilter)
        || (activeTableFilter && activeTableFilter.type === "category" && activeTableFilter.value === def.key);
    if (isActive) tile.classList.add("active");
    tile.innerHTML = `
      <div class="kpi-label">${def.dot ? `<span class="kpi-dot" style="background:${def.dot}"></span>` : ""}${def.label}</div>
      <div class="kpi-value">${value}</div>
    `;
    tile.addEventListener("click", () => {
      if (def.key === "Total") {
        setTableFilter(null);
      } else {
        setTableFilter({ type: "category", value: def.key });
      }
    });
    kpiRow.appendChild(tile);
  });
}

function renderStatusBars(statusCounts) {
  statusBarList.innerHTML = "";
  const max = Math.max(1, ...statusCounts.map((s) => s.count));
  statusCounts.forEach((s) => {
    const pct = Math.round((s.count / max) * 100);
    const row = document.createElement("div");
    row.className = "bar-row";
    if (activeTableFilter && activeTableFilter.type === "status" && activeTableFilter.value === s.name) {
      row.classList.add("active");
    }
    row.title = `Click to filter by ${s.name}`;
    row.innerHTML = `
      <div class="bar-label">${escapeHtml(s.name)}</div>
      <div class="bar-track"><div class="bar-fill" style="width:${pct}%"></div></div>
      <div class="bar-count">${s.count}</div>
    `;
    row.addEventListener("click", () => setTableFilter({ type: "status", value: s.name }));
    statusBarList.appendChild(row);
  });
}

function renderWorkload(workload) {
  workloadBarList.innerHTML = "";
  if (!workload || workload.length === 0) {
    workloadBarList.innerHTML = '<div class="bar-empty">No story points logged for this scope.</div>';
    return;
  }
  const max = Math.max(1, ...workload.map((w) => w.storyPoints));
  workload.forEach((w) => {
    const pct = Math.round((w.storyPoints / max) * 100);
    const row = document.createElement("div");
    row.className = "bar-row" + (w.isCurrentUser ? " bar-row-you" : "");
    row.title = `${w.ticketCount} ticket(s)`;
    row.innerHTML = `
      <div class="bar-label">${escapeHtml(w.assignee)}${w.isCurrentUser ? ' <span class="you-badge">you</span>' : ""}</div>
      <div class="bar-track"><div class="bar-fill" style="width:${pct}%"></div></div>
      <div class="bar-count">${w.storyPoints} pt${w.storyPoints === 1 ? "" : "s"}</div>
    `;
    workloadBarList.appendChild(row);
  });
}

function setTableFilter(filter) {
  // Clicking the already-active filter again clears it.
  if (filter && activeTableFilter && activeTableFilter.type === filter.type && activeTableFilter.value === filter.value) {
    filter = null;
  }
  activeTableFilter = filter;

  if (filter) {
    filterIndicator.hidden = false;
    filterIndicatorLabel.textContent = filter.value;
  } else {
    filterIndicator.hidden = true;
  }

  renderKpiActiveStates();
  renderBarActiveStates();
  renderTicketTable(applyTableFilter(lastIssues));
}

function renderKpiActiveStates() {
  [...kpiRow.children].forEach((tile) => {
    const key = tile.dataset.key;
    const isActive = (key === "Total" && !activeTableFilter)
        || (activeTableFilter && activeTableFilter.type === "category" && activeTableFilter.value === key);
    tile.classList.toggle("active", isActive);
  });
}

function renderBarActiveStates() {
  [...statusBarList.children].forEach((row) => {
    const label = row.querySelector(".bar-label").textContent;
    const isActive = activeTableFilter && activeTableFilter.type === "status" && activeTableFilter.value === label;
    row.classList.toggle("active", isActive);
  });
}

function applyTableFilter(issues) {
  if (!activeTableFilter) return issues;
  if (activeTableFilter.type === "category") {
    return issues.filter((i) => i.statusCategory === activeTableFilter.value);
  }
  return issues.filter((i) => i.status === activeTableFilter.value);
}

clearFilterBtn.addEventListener("click", () => setTableFilter(null));

function renderTicketTable(issues) {
  ticketTableBody.innerHTML = "";
  issues.forEach((issue) => {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td><a href="${issue.url}" target="_blank" rel="noopener">${escapeHtml(issue.key)}</a></td>
      <td><span class="table-status-badge ${statusBadgeClass(issue.statusCategory)}">${escapeHtml(issue.status)}</span></td>
      <td>${escapeHtml(issue.summary)}</td>
      <td>${escapeHtml(issue.assignee)}</td>
      <td>${escapeHtml(issue.priority)}</td>
      <td>${escapeHtml(issue.updated)}</td>
    `;
    ticketTableBody.appendChild(tr);
  });
}

async function loadDashboard() {
  const projectKey = document.getElementById("projectKey").value.trim();
  const currentSprintOnly = document.getElementById("currentSprintOnly").checked;
  const onlyMyTickets = onlyMyTicketsCheckbox.checked;
  const assignee = document.getElementById("assignee").value.trim();
  const dateField = document.getElementById("dateField").value;
  const fromDate = document.getElementById("fromDate").value;
  const toDate = document.getElementById("toDate").value;

  applyBtn.disabled = true;
  applyBtn.textContent = "Loading...";
  dashboardError.hidden = true;

  // A new query invalidates the old client-side table filter.
  activeTableFilter = null;
  filterIndicator.hidden = true;

  try {
    const res = await fetch("/api/dashboard", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        projectKey, currentSprintOnly, onlyMyTickets, assignee,
        issueType: activeIssueType, dateField, fromDate, toDate,
      }),
    });
    const data = await res.json();

    if (!data.ok) {
      dashboardError.textContent = data.message || "Something went wrong.";
      dashboardError.hidden = false;
      dashboardContent.hidden = true;
      dashboardEmpty.hidden = true;
      return;
    }

    lastIssues = data.issues;
    renderKpis(data.categoryCounts, data.total);
    renderStatusBars(data.statusCounts);
    renderWorkload(data.workload);
    renderTicketTable(lastIssues);
    ticketCount.textContent = data.total;
    dashboardJql.textContent = data.jql;
    workloadJql.textContent = data.workloadJql || "";

    dashboardContent.hidden = false;
    dashboardEmpty.hidden = true;
  } catch (err) {
    dashboardError.textContent = "Could not reach the local server: " + err.message;
    dashboardError.hidden = false;
  } finally {
    applyBtn.disabled = false;
    applyBtn.textContent = "Apply";
  }
}

dashboardFilters.addEventListener("submit", (e) => {
  e.preventDefault();
  loadDashboard();
});
