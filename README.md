# Spring AI MCP POC

A Spring Boot + Spring AI proof-of-concept that accepts prompts via REST API and orchestrates responses using **Claude** or **GitHub Copilot/Models**, with tool access to enterprise MCP servers (Git, JFrog, Jira, Confluence, ServiceNow).

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      Spring AI MCP POC                           │
│                                                                  │
│  ┌──────────┐    ┌─────────────────┐    ┌───────────────────┐  │
│  │  REST    │───▶│  Orchestration  │───▶│  AI Providers     │  │
│  │  API     │    │  Service        │    │  - Claude (Anthr) │  │
│  │ /prompt  │    │                 │    │  - GitHub Models  │  │
│  └──────────┘    └────────┬────────┘    └───────────────────┘  │
│                           │                                      │
│                           ▼                                      │
│              ┌────────────────────────────┐                     │
│              │     MCP Tool Callbacks     │                     │
│              └─────┬──┬──┬──┬──┬─────────┘                     │
│                    │  │  │  │  │                                 │
└────────────────────┼──┼──┼──┼──┼────────────────────────────────┘
                     │  │  │  │  │
         ┌───────────┘  │  │  │  └───────────┐
         ▼              ▼  ▼  ▼              ▼
    ┌─────────┐  ┌─────┐ ┌────┐ ┌──────────┐ ┌───────────┐
    │  Git    │  │JFrog│ │Jira│ │Confluence│ │ServiceNow │
    │  MCP    │  │ MCP │ │MCP │ │   MCP    │ │    MCP    │
    │ Server  │  │Srvr │ │Srvr│ │  Server  │ │   Server  │
    └─────────┘  └─────┘ └────┘ └──────────┘ └───────────┘
```

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker & Docker Compose (for MCP servers)

## Using Your Existing Copilot & Claude Access

### Option 1: Claude via Anthropic API Key

If you have a **Claude/Anthropic subscription** (personal or via Schneider Electric):

1. Go to [Anthropic Console](https://console.anthropic.com/settings/keys)
2. Generate an API key
3. Set it as environment variable:
   ```bash
   export ANTHROPIC_API_KEY=sk-ant-api03-your-key-here
   ```

### Option 2: Claude via AWS Bedrock (Enterprise)

If your organization provides Claude access through **AWS Bedrock**:

Replace the Anthropic dependency in `pom.xml` with:
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-bedrock-ai-spring-boot-starter</artifactId>
</dependency>
```

And configure in `application.yml`:
```yaml
spring:
  ai:
    bedrock:
      aws:
        region: us-east-1
        access-key: ${AWS_ACCESS_KEY_ID}
        secret-key: ${AWS_SECRET_ACCESS_KEY}
      anthropic:
        chat:
          model: anthropic.claude-sonnet-4-20250514-v1:0
```

### Option 3: GitHub Copilot via GitHub Models API

If you have **GitHub Copilot** access (which includes GitHub Models):

1. Go to [GitHub Settings > Tokens](https://github.com/settings/tokens)
2. Create a token with `models:read` scope
3. The project is already configured to use the GitHub Models inference endpoint:
   ```yaml
   spring:
     ai:
       openai:
         api-key: ${GITHUB_TOKEN}
         base-url: https://models.inference.ai.azure.com
   ```

This uses the **OpenAI-compatible** endpoint from GitHub Models, giving you access to GPT-4o, Claude, and other models through your Copilot subscription.

### Option 3b: GitHub Copilot SDK (direct Copilot sessions)

This project now also supports a direct provider path using the Java Copilot SDK.

1. Enable Copilot SDK in your environment:
   ```bash
   export COPILOT_SDK_ENABLED=true
   export COPILOT_MODEL=gpt-5
   ```
2. Ensure your machine is already authenticated for GitHub Copilot.
3. Call the prompt API with `"provider": "copilot"`.

Example:
```bash
curl -X POST http://localhost:8080/api/v1/ai/prompt \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "copilot",
    "mode": "chat",
    "prompt": "Summarize this Java class"
  }'
```

Notes:
- `provider: "springai"` remains the default and keeps MCP tool-calling support.
- `provider: "copilot"` uses Copilot SDK sessions directly.
- MCP tool callbacks are executed in the Spring AI path.

### Option 4: Azure OpenAI (Enterprise)

If your organization provides access via **Azure OpenAI**:

```yaml
spring:
  ai:
    azure:
      openai:
        api-key: ${AZURE_OPENAI_API_KEY}
        endpoint: https://your-resource.openai.azure.com/
        chat:
          options:
            deployment-name: gpt-4o
            temperature: 0.7
```

## Quick Start

### 1. Clone and configure

```bash
cd spring-ai-mcp-poc
cp .env.example .env
# Edit .env with your actual credentials
```

### 2. Start MCP Servers

```bash
docker-compose up -d
```

### 3. Run the application

```bash
# Using Maven
mvn spring-boot:run

# Or with environment variables inline
ANTHROPIC_API_KEY=your-key mvn spring-boot:run
```

### 4. Test the API

```bash
# Simple prompt (uses Claude by default)
curl -X POST http://localhost:8080/api/v1/ai/prompt \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "List all open Jira tickets assigned to me",
    "provider": "claude",
    "enabledTools": ["jira"]
  }'

# Use GitHub Copilot/Models
curl -X POST http://localhost:8080/api/v1/ai/prompt \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "Create a new branch named feature/payment-fix in the main repo",
    "provider": "copilot",
    "enabledTools": ["git"]
  }'

# Multi-tool prompt
curl -X POST http://localhost:8080/api/v1/ai/prompt \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "Find the latest build artifact for mdm-api in JFrog and create a ServiceNow change request for its deployment",
    "provider": "claude",
    "enabledTools": ["jfrog", "servicenow"]
  }'

# List available tools
curl http://localhost:8080/api/v1/ai/tools
```

## MCP Server Details

| Server | Port | Tools Provided |
|--------|------|---------------|
| **Git** | 3001 | create_repository, create_branch, create_pull_request, search_code, list_commits, etc. |
| **JFrog** | 3002 | search_artifacts, deploy_artifact, get_build_info, list_repos, etc. |
| **Jira** | 3003 | create_issue, search_issues, update_issue, transition_issue, add_comment, etc. |
| **Confluence** | 3004 | create_page, search_content, update_page, get_page, list_spaces, etc. |
| **ServiceNow** | 3005 | create_incident, update_incident, create_change_request, search_knowledge, etc. |

## MCP Server Alternatives (STDIO Transport)

If you prefer running MCP servers locally via `npx` (STDIO transport instead of SSE), you can configure them in `application.yml`:

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          servers:
            github:
              command: npx
              args:
                - -y
                - "@modelcontextprotocol/server-github"
              env:
                GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}
            jfrog:
              command: npx
              args:
                - -y
                - "@jfrog/mcp-server-jfrog"
              env:
                JFROG_URL: ${JFROG_URL}
                JFROG_ACCESS_TOKEN: ${JFROG_ACCESS_TOKEN}
```

## Project Structure

```
spring-ai-mcp-poc/
├── pom.xml                          # Maven dependencies (Spring AI, MCP, Anthropic, OpenAI)
├── docker-compose.yml               # MCP servers (Git, JFrog, Jira, Confluence, ServiceNow)
├── .env.example                     # Environment variable template
├── src/main/java/com/schneider/ai/
│   ├── SpringAiMcpPocApplication.java    # Main entry point
│   ├── config/
│   │   └── McpServerConfig.java          # MCP server connections & tool registration
│   ├── controller/
│   │   └── PromptController.java         # REST API endpoints
│   ├── model/
│   │   ├── PromptRequest.java            # Request DTO
│   │   └── PromptResponse.java           # Response DTO
│   └── service/
│       └── AiOrchestrationService.java   # AI orchestration with tool calling
└── src/main/resources/
    └── application.yml                    # Configuration
```

## How It Works

1. **User sends a prompt** via `POST /api/v1/ai/prompt`
2. **Provider selection**: Routes to Claude (Anthropic) or GitHub Models (OpenAI-compatible) based on `provider` field
3. **MCP Tool binding**: Enabled MCP tools are attached to the chat request
4. **AI processes the prompt**: The model decides which tools to call based on the prompt
5. **Tool execution**: Spring AI automatically executes MCP tool calls and feeds results back to the model
6. **Response returned**: Final AI response with tool usage metadata

## Extending

### Adding a new MCP Server

1. Add the server URL config in `application.yml`
2. Add connection logic in `McpServerConfig.java`
3. The tools are automatically available to the AI model

### Switching AI Providers

Change the `provider` field in your API request:
- `"claude"` - Uses Anthropic Claude
- `"copilot"` or `"github"` - Uses GitHub Models API
- Default is `"claude"` if not specified

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `401 Unauthorized` from Anthropic | Check `ANTHROPIC_API_KEY` is valid |
| `403` from GitHub Models | Ensure token has `models:read` scope |
| MCP server connection failed | Verify Docker containers are running: `docker-compose ps` |
| No tools available | Check MCP server logs: `docker-compose logs <server-name>` |

## References

- [Spring AI Documentation](https://docs.spring.io/spring-ai/reference/)
- [Spring AI MCP Client](https://docs.spring.io/spring-ai/reference/api/mcp-client.html)
- [Model Context Protocol Spec](https://modelcontextprotocol.io/)
- [MCP Server Registry](https://github.com/modelcontextprotocol/servers)
- [GitHub Models](https://github.com/marketplace/models)
- [Anthropic API](https://docs.anthropic.com/en/docs/about-claude/models)
