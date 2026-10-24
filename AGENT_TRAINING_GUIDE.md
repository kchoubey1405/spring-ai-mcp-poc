# IFW Assistant - Agent Training Guide

**Last Updated**: August 31, 2026  
**Version**: 1.0  
**Configuration**: Spring Boot 3.4.4 + Spring AI MCP + GitHub Copilot SDK

---

## Overview

This document provides complete training and configuration for the EMH AI ASSISTANT agent to work perfectly with your Jira, Git, and Confluence MCP integrations.

## System Configuration

### Framework Stack
- **Language**: Java 17
- **Framework**: Spring Boot 3.4.4
- **AI Provider**: GitHub Copilot SDK (com.github.copilot:copilot-sdk-java:1.0.13-preview.2)
- **Integration Protocol**: MCP (Model Context Protocol)
- **Build Tool**: Maven 3.9+

### MCP Servers Connected
1. **Jira MCP Server** - Atlassian Data Center Jira (v0.33.0)
2. **Git MCP Server** - GitHub Enterprise (v0.6.2)
3. **Confluence MCP Server** - Atlassian Confluence (v0.33.0)

**Total Available Tools**: 46 MCP tools across all three servers

---

## Critical Configuration: Your Jira Instance

### Field Name Mapping (CASE-SENSITIVE)

Your Jira instance uses these exact field names:

| Field Type | Field Name | Usage Example |
|-----------|-----------|----------------|
| Component | `Components` | `Components in ("EMH", "EMH-Middleware")` |
| Label | `labels` | `labels in ("EMH", "EMH-Middleware")` |
| Status | `status` | `status NOT IN ("Closed", ...)` |

**⚠️ CRITICAL**: These field names are case-sensitive. Using lowercase `component` will cause Jira query failures.

### Default Status Exclusions

Always exclude these statuses unless user explicitly requests them:
- `Closed`
- `In Production`
- `Cancelled`

### Default Component

When user does NOT specify a component/project name, **always assume EMH**:
- Components: `EMH`, `EMH-Middleware`
- Do NOT default to `project = EMH` (that's a fallback only)

---

## System Prompt Configuration

**File**: `src/main/resources/prompts/ifw-assistant-system-prompt.txt`

### Key Directives in System Prompt

1. **Tool Invocation Format (Agent Mode Only)**
   ```
   TOOL_CALL: tool_name(parameter1: value1, parameter2: value2)
   ```

2. **Jira Search Tool Invocation**
   ```
   TOOL_CALL: spring_ai_mcp_client_jira_mcp_server_jira_searchIssues(jql: YOUR_JQL_QUERY)
   ```

3. **Default EMH Query (Copy-Paste Ready)**
   ```jql
   (Components in ("EMH", "EMH-Middleware") OR labels in ("EMH", "EMH-Middleware")) AND status NOT IN ("Closed", "In Production", "Cancelled")
   ```

### System Prompt Principles

- ✅ DO: Execute tool calls BEFORE answering
- ✅ DO: Use exact JQL field names (Components with capital C)
- ✅ DO: Base responses on actual tool results
- ✅ DO: Return structured output (tables/bullet lists)

- ❌ DON'T: Say "I would use tool X"
- ❌ DON'T: Claim lack of tool access in agent mode
- ❌ DON'T: Skip tool invocation
- ❌ DON'T: Use lowercase `component` field

---

## Skill Files for Agent Training

### Built-in runtime skills (loaded by the application)
Skills in `src/main/resources/prompts/skills/*.txt` are loaded at startup and injected into the agent prompt by intent:

| Skill | Injected when | Purpose |
|-------|---------------|---------|
| `jira-repo-context.txt` | Jira, Git, change request or a Jira key in the prompt | GitHub Enterprise org `ifw-microservices`, branch/PR conventions, repo catalog (snapshot, edit as repos change) |
| `jira-change-to-pr.txt` | "make the change / raise a PR / implement KEY" | Workflow: Jira, Confluence, branch from develop first, analyze in feature branch only, show diff, take consent, make changes, compile & test, run spring-boot:run verify logs, raise PR targeting develop |
| `confluence-research.txt` | Always in agent mode | CQL patterns, space hints (CDS, GOD, OOD, DATE), citing pages |

Agent loop: up to 12 tool rounds per request (8 calls per round). Code-enforced guards: merge/delete/create/fork repo tools are blocked, and commits or PR heads on protected branches (develop, master, main, release/*, hotfix/*) are rejected.

### Skill 1: Jira MCP Search
**File**: `c:\Users\SESA720729\.agents\skills\jira-mcp-search\SKILL.md`

**Purpose**: Train agent on Jira search patterns, JQL syntax, and field names

**Covers**:
- Default EMH queries
- Component-specific searches
- Status filtering
- Common mistakes to avoid
- Examples and troubleshooting

**Triggers: WHEN**
- User asks for "Jira tickets"
- User says "show me EMH issues"
- User wants to search/filter by component or status
- User requests current/open tasks

**Key Methods**:
1. Identify request type (default EMH, specific component, specific status)
2. Build correct JQL query with Components field
3. Invoke Jira MCP tool
4. Parse and format results
5. Present in readable format (bullet points or table)

---

## Code Architecture

### Main Orchestration Service
**File**: `src/main/java/com/schneider/ai/service/AiOrchestrationService.java`

**Key Methods**:

1. **`processPrompt()`** (Lines 62-100)
   - Entry point for all prompt requests
   - Routes between chat and agent modes

2. **`buildJiraJqlFromPrompt()`** (Lines 996-1030)
   - Generates JQL query from user prompt
   - Detects status filters from user intent
   - Logs generated query for debugging

3. **`buildProjectOrComponentClause()`** (Lines 1037-1081)
   - Creates correct Components/labels clause
   - Maps user intent to field values
   - Uses `Components` (capital C) exclusively

4. **`buildJiraJqlCandidates()`** (Lines 580-620)
   - Generates fallback JQL variants
   - Primary candidate: generated JQL
   - Fallbacks: project key, alternative syntax
   - Logs all candidates for debugging

5. **`tryToolPayloads()`** (Lines 625-657)
   - Tries multiple JQL candidates sequentially
   - Detects Jira errors (`"success":false`)
   - Continues to next candidate on failure
   - Returns first successful result

6. **`executeToolInvocation()`** (Lines 540-556)
   - Executes parsed tool call
   - Normalizes parameters to JSON
   - Returns tool result or error

---

## Execution Flow Diagram

```
User Request
    ↓
processPrompt() → Detect intent (Jira/Git/Confluence?)
    ↓
[JIRA DETECTED]
    ↓
buildJiraJqlFromPrompt() → Generate JQL with Components field
    ↓
Copilot SDK → (if agent mode enabled)
    ↓
detectToolInvocations() → Parse "TOOL_CALL: jira_searchIssues(...)"
    ↓
buildJiraJqlCandidates() → [Primary JQL, Fallback 1, Fallback 2, ...]
    ↓
tryToolPayloads() → Try candidates sequentially
    ├─ Candidate 1 (Generated JQL) → Success? Return result
    ├─ Candidate 2 (project = EMH) → Success? Return result
    └─ Candidate 3+ (alternatives) → Continue...
    ↓
Format Response → Present to user (table/bullets)
```

---

## Environment Configuration

### Application Properties
**File**: `src/main/resources/application.properties`

**Critical Settings**:

```properties
# Copilot SDK Configuration
copilot.sdk.enabled=true
copilot.sdk.model=gpt-5

# MCP Server Connections
spring.ai.mcp.client.stdio.connections.jira-mcp-server.env.JIRA_HOST=${JIRA_URL}
spring.ai.mcp.client.stdio.connections.jira-mcp-server.env.JIRA_EMAIL=${JIRA_EMAIL}
spring.ai.mcp.client.stdio.connections.jira-mcp-server.env.JIRA_API_TOKEN=${JIRA_API_TOKEN}

# Timeout Settings
spring.ai.mcp.server.request-timeout-seconds=180
copilot.sdk.timeout-seconds=60
```

**Environment Variables Required**:
- `JIRA_URL` - Your Jira instance URL
- `JIRA_EMAIL` - Jira user email
- `JIRA_API_TOKEN` - Jira API token
- `GITHUB_ENTERPRISE_TOKEN` - For Git operations
- `CONFLUENCE_URL` - Confluence instance URL
- `CONFLUENCE_EMAIL` - Confluence user email
- `CONFLUENCE_API_TOKEN` - Confluence API token
- `JFROG_ACCESS_TOKEN` (fallback `JFROG_API_TOKEN`) and `JFROG_URL` - JFrog / Xray access

---

## JFrog / Xray Vulnerability Management (in-process Spring AI tools)

The org JFrog MCP is not enabled, so the same capability is implemented as local `@Tool` beans
(published by `config/LocalToolsConfig`) that call the JFrog REST APIs directly.

### Configuration
| Setting | Purpose |
|---|---|
| `JFROG_ACCESS_TOKEN` / `JFROG_URL` | Bearer token and base URL (`https://global-artifacts.se.com`) |
| `jfrog.docker-repo` | Docker repo scanned for the latest release of each module (`sd-ei-emh-eu-docker-prod-fed`) |
| `jfrog.maven-repo` | Maven repo used to look up vulnerability-free dependency versions (`libs-release-prod-virtual`) |
| `workspace.dir` | Sandbox for cloned repos (default `~/.emh-ai-workspace`) |
| `GITHUB_ENTERPRISE_TOKEN` | Clone, push and PR creation on GHES (org `ifw-microservices`) |

### Tools
- `xray_getVulnerabilityCounts`, `xray_getVulnerabilityDetails`, `xray_createReportLink`
- `jfrog_listProjects`, `jfrog_listImageTags`
- `maven_findSafeVersion` (lowest stable, same-major version that fixes the CVEs)
- `workspace_cloneRepo`, `workspace_createBranch`, `workspace_listFiles`, `workspace_readFile`,
  `workspace_searchText`, `workspace_replaceInFile`, `workspace_writeFile`, `workspace_runMaven`,
  `workspace_gitStatus`, `workspace_gitDiff`, `workspace_commitAndPush`, `workspace_createPullRequest`

### Behaviour rules
- Counts and reports use the latest release tag per module unless a release or module is named. The default
  scope is all projects, module-wise. If the latest tag is not indexed by Xray, up to 2 older releases are
  tried and the answer states which release was used.
- "Fix vulnerabilities": the agent always asks for the release first (`VulnerabilityChatService`), then follows
  `prompts/skills/vulnerability-fix.txt`: clone, `feature/vulnerability-fix-*` branch, upgrade dependencies,
  `mvn clean install`, commit, push, PR link.
- Guards in `WorkspaceTools`: feature branches only, Maven goal/flag allow-list, no `target/`, `.env`,
  jar/class/key files in commits, secret scan on the diff, tokens scrubbed from output.
- OS / base-image findings (deb/apk) cannot be fixed by a pom change; report them as an infra change.

### REST endpoints (right-panel UI)
- `GET /api/v1/dashboard/vulnerabilities` - summary (status LOADING/READY, projects, modules, totals)
- `POST /api/v1/dashboard/vulnerabilities/refresh` - force a rebuild
- `GET /api/v1/dashboard/vulnerabilities/query?project=&module=&version=`
- `GET /api/v1/dashboard/vulnerabilities/report?project=&module=&version=&detail=` - CSV download
  (all projects when `project` is omitted)

### Skills
`prompts/skills/vulnerability-report.txt`, `prompts/skills/vulnerability-fix.txt`.

---

## Right-Panel Live Cards (Jira / Pull Requests / Kafka)

Served by `DashboardService` (cached 300 s, loaded at startup, last good value kept with `stale:true` on failure, `status:"ERROR"` + `message` if nothing is available - never fake data).

| Endpoint | Content |
|----------|---------|
| `GET /api/v1/dashboard` | `{jira, pullRequests, kafka, vulnerabilities}` |
| `GET /api/v1/dashboard/jira` | EMH Jira total + status-wise counts (JQL = `dashboard.jira.jql`, default = chat EMH default JQL) |
| `GET /api/v1/dashboard/prs` | Open PRs across the org: total + per-project counts |
| `GET /api/v1/dashboard/kafka` | Kafka Connect: total, running, paused, failed, other, not-running list with `failedTasks` and `failedTaskIds` |
| `POST /api/v1/dashboard/kafka/connectors/{connector}/tasks/{taskId}/restart` | Restart a specific failed task of a connector |
| `POST /api/v1/dashboard/kafka/connectors/{connector}/restart` | Restart the entire Kafka connector |
| `POST /api/v1/dashboard/kafka/connectors/{connector}/restart-failed` | Restart all failed tasks of a connector |

All `GET` endpoints accept `?refresh=true`.

Properties: `dashboard.jira.jql` (`DASHBOARD_JIRA_JQL`), `dashboard.cache-ttl-seconds` (`DASHBOARD_CACHE_TTL_SECONDS`), plus existing `dashboard.kafka.*`, `dashboard.github.*`, `dashboard.jira.*`.

**Kafka classification:** connector is FAILED if its state is FAILED **or any task is FAILED** (even when other tasks run); PAUSED if state PAUSED; RUNNING if RUNNING; otherwise OTHER.

### Kafka Connector Failed Task Restart (UI & Agent Tool)
- **UI Right Panel**: Right beside each failed task in the Kafka card, a 1-click **Restart Task X** button triggers the restart directly and refreshes the card.
- **REST API Call**:
  ```bash
  curl --location --request POST 'https://uat.emh.ei.se.com/api/kafka-connect-1/connectors/{connectorName}/tasks/{taskId}/restart' \
  --header 'Authorization: Basic U2VFbWhUZWFtOldlbGNvbWUx'
  ```
- **Agent Tools & Skill (`kafka-connect-operations`)**:
  - `kafka_getConnectorsSummary(refresh)`: Query connector status and failed tasks.
  - `kafka_restartConnectorTask(connector, taskId)`: Restart a specific failed task.
  - `kafka_restartFailedTasks(connector)`: Restart all failed tasks for a connector.

---

## Common Queries and Expected Behavior

### Example 1: Default EMH Request

**User Input**:
```
"Give me the list of EMH jira tickets"
```

**Expected Agent Behavior**:
1. Recognizes "EMH jira tickets" → Jira request
2. No specific status mentioned → Use default exclusions
3. Generates JQL:
   ```jql
   (Components in ("EMH", "EMH-Middleware") OR labels in ("EMH", "EMH-Middleware")) AND status NOT IN ("Closed", "In Production", "Cancelled")
   ```
4. Invokes Jira tool:
   ```
   TOOL_CALL: spring_ai_mcp_client_jira_mcp_server_jira_searchIssues(jql: (Components in ("EMH", "EMH-Middleware") OR labels in ("EMH", "EMH-Middleware")) AND status NOT IN ("Closed", "In Production", "Cancelled"))
   ```
5. Format results as table/bullet list with key, summary, status, assignee

### Example 2: Specific Component Request

**User Input**:
```
"Show me open payment module tickets"
```

**Expected Agent Behavior**:
1. Recognizes "payment module" → Specific component
2. Detects "open" → Status filter
3. Generates JQL:
   ```jql
   (Components in ("payment") OR labels in ("payment")) AND status = "Open"
   ```
4. Returns formatted results

### Example 3: Status Override

**User Input**:
```
"What's closed in EMH"
```

**Expected Agent Behavior**:
1. Recognizes EMH + "closed" → Status override
2. Generates JQL:
   ```jql
   (Components in ("EMH", "EMH-Middleware") OR labels in ("EMH", "EMH-Middleware")) AND status = "Closed"
   ```
4. Returns closed tickets for EMH

### Example 4: Pagination Request

**User Input** (After receiving 25 results):
```
"Show me the next page"
```

**Expected Agent Behavior**:
1. Recognizes "next page" → Pagination request
2. Reuses the JQL of the previous search (stored per session) unchanged - JQL has NO `OFFSET` clause
3. Calls `jira_searchIssues` with `{jql: <same JQL>, startAt: 25, maxResults: 25}`
4. Returns page 2 as a table with "Showing 26-50 of <total>" and a "next 25" hint

This is handled deterministically in `AiOrchestrationService.tryJiraPage` (no model call).

---

## Pagination Support

Paging uses the Jira tool's `startAt` / `maxResults` arguments. **JQL has no OFFSET clause** - a query ending in `OFFSET n` is invalid.

### How It Works

1. Every `jira_searchIssues` call (model-issued or fallback) is recorded per session as `JiraPaging.State(jql, startAt, pageSize)`. Arguments are sanitized: any `OFFSET n` is stripped from the JQL and moved into `startAt`, `ORDER BY created DESC` is added if missing (stable paging), `maxResults` defaults to 25.
2. "next 25", "next page", "page 3", "previous page", "show more tickets" are detected by `JiraPaging.isPagination`.
3. `tryJiraPage` re-runs the stored JQL with the new `startAt` and renders a table with the range and a next/last-page hint.
4. Works only after a Jira list in the same session.

### Calculation Examples

| Page | startAt | Items Returned |
|------|---------|-----------------|
| 1 (First) | 0 | Items 1-25 |
| 2 (Next) | 25 | Items 26-50 |
| 3 | 50 | Items 51-75 |
| 4 | 75 | Items 76-100 |
| 9 | 200 | Items 201+ |

### System Prompt Directive

The system prompt explicitly instructs the agent:

```
NEVER refuse tool execution for pagination, next page, or continuation requests
NEVER say "I don't have pagination support" or "I can't fetch more results"
ALWAYS execute the tool even if you think pagination might not be supported
```

### User Request Patterns

These all trigger pagination logic:

- "next page"
- "page 2"
- "show me page 3"
- "more results"
- "fetch more"
- "next 25"
- "continue"
- "show the rest"
- "what else"

---



### Enable Debug Logging

**File**: `application.properties`
```properties
logging.level.com.schneider.ai.service.AiOrchestrationService=DEBUG
logging.level.com.schneider.ai.service.CopilotSdkService=DEBUG
```

### Key Log Messages to Monitor

1. **JQL Generation**:
   ```
   Generated Jira JQL Query: (Components in ("EMH", "EMH-Middleware") OR labels in ("EMH", "EMH-Middleware")) AND status NOT IN (...)
   ```

2. **Tool Invocation**:
   ```
   Attempting candidate 1 of 6: {"jql":"(Components in ("EMH", ..."}
   ```

3. **Fallback Activation**:
   ```
   ✗ Candidate 1 returned Jira error (success=false), trying next candidate...
   Attempting candidate 2 of 6: {"jql":"project = EMH ...
   ```

4. **Success**:
   ```
   ✓ Candidate 1 succeeded! Result length: 1234 chars
   Result preview: [{"key":"EMH-123","summary":"..."}]
   ```

### Common Issues and Fixes

| Issue | Cause | Fix |
|-------|-------|-----|
| "Components is not a valid component value" | Using lowercase 'component' | Use `Components` with capital C |
| No results returned | Wrong component name | Verify component exists in Jira |
| Tool times out | Jira server slow | Increase `spring.ai.mcp.server.request-timeout-seconds` |
| Tool invocation not executing | Agent mode disabled | Set `copilot.sdk.enabled=true` |
| Query fails, then succeeds on retry | First candidate doesn't work | Fallback logic handles this automatically |

---

## Deployment Checklist

- [ ] System prompt updated with correct Components field names
- [ ] Environment variables set (JIRA_URL, JIRA_EMAIL, JIRA_API_TOKEN, etc.)
- [ ] Jira skill file created and trained into agent
- [ ] Code compiled without errors
- [ ] Application started successfully
- [ ] MCP servers connected (check logs: "Jira MCP Server response...")
- [ ] Test query executed successfully (e.g., "Give me EMH tickets")
- [ ] Results returned in expected format
- [ ] Fallback logic tested (verify logs show attempts)

---

## Next Steps for Optimization

1. **Monitor Performance**: Track query response times and success rates
2. **Refine Field Mapping**: If new components added, update system prompt
3. **Extend Skills**: Create skills for Git PR queries, Confluence searches
4. **User Training**: Share this guide with team using the agent
5. **Feedback Loop**: Collect user feedback and refine JQL patterns

---

## Support and References

- **Jira Query Language (JQL)**: https://support.atlassian.com/jira-cloud-platform/docs/jira-query-language-jql/
- **MCP Protocol**: https://modelcontextprotocol.io/
- **GitHub Copilot SDK**: https://github.com/github/copilot-sdk-java
- **Spring AI**: https://spring.io/projects/spring-ai

---

## Version Control

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-08-31 | Initial training guide based on Components (capital C) configuration |

