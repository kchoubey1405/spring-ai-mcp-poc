package com.schneider.ai.service;

import com.schneider.ai.model.ChatStreamEvent;
import com.schneider.ai.model.PromptRequest;
import com.schneider.ai.model.PromptResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class AiOrchestrationService {

    private final List<ToolCallback> mcpTools;
    private final ApplicationContext applicationContext;
    private final ChatMemoryService chatMemoryService;
    private final Map<String, LlmProviderService> providers = new ConcurrentHashMap<>();
    private final String configuredProvider;
    private final String configuredModel;
    private final String systemPrompt;
    private final String defaultGitOrg;
    /** Last Jira search (JQL, startAt, page size) per chat session; "next 25" continues from it. */
    private final Map<String, JiraPaging.State> jiraLastSearch = new ConcurrentHashMap<>();
    private final Map<String, String> lastIntentBySession = new ConcurrentHashMap<>();
    private final Map<String, SessionRemediationState> sessionRemediationStates = new ConcurrentHashMap<>();
    private static final int JIRA_PAGE_SIZE = JiraPaging.DEFAULT_PAGE_SIZE;

    @Value("${dashboard.jira.base-url:https://jira.se.com}")
    private String jiraBaseUrl = "https://jira.se.com";

    /** Deterministic vulnerability flows (counts, reports, ask-for-release). Optional so unit tests can omit it. */
    @Autowired(required = false)
    private VulnerabilityChatService vulnerabilityChat;

    @Autowired(required = false)
    private com.schneider.ai.auth.SessionAuthService sessionAuthService;

    // ---- Agent loop limits ----
    private static final int MAX_AGENT_ROUNDS = 12;
    /** A vulnerability fix is a long workflow: clone, edit several poms, build, fix tests, commit, PR. */
    private static final int MAX_FIX_ROUNDS = 40;
    private static final int MAX_VULN_REPORT_ROUNDS = 8;
    private static final List<String> LOCAL_TOOL_PREFIXES = List.of("jfrog_", "xray_", "maven_", "workspace_", "kafka_", "servicenow_", "outlook_", "report_");
    private static final int MAX_TOOL_CALLS_PER_ROUND = 8;
    private static final int MAX_TOOL_CALL_LINES = 400;
    private static final int MAX_TRANSCRIPT_CHARS = 100_000;
    private static final int MAX_SINGLE_RESULT_CHARS = 40_000;
    private static final int MAX_PREFETCH_CHARS = 15_000;

    // ---- Tool argument typing (MCP servers validate number/boolean types strictly) ----
    private static final Set<String> NUMERIC_TOOL_PARAMS = Set.of(
            "pullNumber", "issue_number", "issueNumber", "page", "perPage", "per_page",
            "limit", "start", "startAt", "maxResults", "maxInlineBytes", "maxBodyChars", "bodyStart", "version",
            "maxComponents", "startLine", "endLine", "timeoutMinutes");
    private static final Set<String> BOOLEAN_TOOL_PARAMS = Set.of(
            "draft", "recursive", "minimal_output", "maintainer_can_modify", "returnContent", "save",
            "detail", "replaceAll", "regex", "forceRefresh");

    // ---- Write guards (enforced in code, independent of what the model decides) ----
    private static final Set<String> BLOCKED_TOOL_SUFFIXES = Set.of(
            "merge_pull_request", "delete_file", "create_repository", "fork_repository");
    private static final Set<String> WRITE_TO_BRANCH_TOOL_SUFFIXES = Set.of("push_files", "create_or_update_file");
    private static final Set<String> PROTECTED_BRANCHES = Set.of(
            "develop", "development", "dev", "master", "main", "trunk", "staging", "production", "prod");

    // ---- Intent detection ----
    private static final Set<String> NON_ISSUE_KEY_PREFIXES = Set.of(
            "UTF", "SHA", "ISO", "HTTP", "HTTPS", "TLS", "SSL", "AES", "RSA", "CVE", "RFC", "JDK", "TCP", "UDP");
    private static final Pattern ISSUE_KEY_PATTERN = Pattern.compile("\\b([A-Z][A-Z0-9]{1,9})-(\\d{1,6})\\b");
    private static final Pattern CREATE_PR_PATTERN = Pattern.compile(
            "\\b(raise|create|submit|send|generate|prepare)\\b.{0,40}?\\b(pr|prs|pull[- ]?requests?|merge requests?)\\b"
                    + "|\\bopen (a|an|new|the) (pr|pull[- ]?request)\\b");
    private static final Pattern CONFIRM_CHANGE_PATTERN = Pattern.compile(
            "\\b(accept|confirm|approve|approved|proceed|apply|go ahead|lgtm|looks good)\\b.{0,40}?\\b(changes?|pr|pull[- ]?requests?|file|diff|proposal)?\\b"
                    + "|^(yes|proceed|accept|approved?|confirmed?|lgtm|looks good|go ahead)(!|\\.)?$"
                    + "|\\b(create|raise|open)\\b.{0,20}?\\b(the\\s+)?(pr|pull[- ]?request)\\b");
    private static final Pattern CHANGE_WORK_PATTERN = Pattern.compile(
            "\\b(make|apply|commit)\\b.{0,30}?\\b(changes?|fix(es)?|modifications?|updates?)\\b");
    private static final Pattern CHANGE_ON_ISSUE_PATTERN = Pattern.compile(
            "\\b(implement|work on|resolve|fix|develop)\\b.{0,40}?\\b(jira|ticket|issue|story|bug|defect|requirement|[a-z][a-z0-9]{1,9}-\\d{1,6})\\b");
    private static final Pattern GIT_WORD_PATTERN = Pattern.compile(
            "\\b(git|github|repo|repos|repository|repositories|pr|prs|pull[- ]?requests?|branch|branches|commit|commits|ifw-microservices)\\b");
    private static final Pattern KNOWLEDGE_PATTERN = Pattern.compile(
            "^(what|who|where|when|why|how|which|explain|describe|tell me|give me|show me|summari[sz]e|find|search|look ?up|list)\\b");

    private final Map<String, String> skills = new LinkedHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

        private static final String FALLBACK_SYSTEM_PROMPT = """
            You are EMH AI ASSISTANT, an enterprise AI agent.
            In agent mode, use MCP tools for Jira, Git, and Confluence requests.
            For Jira requests, generate JQL and use it in Jira MCP calls.
            For EMH Jira requests, prioritize components EMH and EMH-Middleware.
            Default Jira filter excludes Closed, Cancelled, and In Production unless user asks specific statuses.
            When proposing code changes, present the diff to the user and ask for confirmation before creating branches or raising PRs.
            If greeted with hi/hello/hey, reply: Hello, I am EMH AI ASSISTANT. How can I help you today?
            """;

    @Autowired
    public AiOrchestrationService(
            @Autowired(required = false) List<ToolCallback> mcpTools,
            ApplicationContext applicationContext,
            ChatMemoryService chatMemoryService,
            @Autowired(required = false) List<LlmProviderService> llmProviders,
            @Value("${ai.provider:copilot}") String configuredProvider,
            @Value("${copilot.sdk.model:gpt-5}") String configuredModel,
            @Value("${dashboard.github.org:}") String defaultGitOrg,
            @Value("classpath:prompts/ifw-assistant-system-prompt.txt") Resource systemPromptResource) {

        this.applicationContext = applicationContext;
        this.chatMemoryService = chatMemoryService;
        this.configuredProvider = configuredProvider != null ? configuredProvider : "copilot";
        this.configuredModel = configuredModel;
        this.defaultGitOrg = defaultGitOrg;
        this.systemPrompt = loadSystemPrompt(systemPromptResource);
        loadSkills();

        if (llmProviders != null) {
            for (LlmProviderService provider : llmProviders) {
                this.providers.put(provider.getProviderName().toLowerCase(), provider);
            }
        }
        log.info("Registered LLM providers: {}, configured default: {}",
                this.providers.keySet(), this.configuredProvider);

        this.mcpTools = mcpTools != null ? mcpTools : Collections.emptyList();
        List<ToolCallback> detectedAtStartup = currentMcpTools();
        log.info("MCP tools detected at startup: count={}, names={}", detectedAtStartup.size(),
            detectedAtStartup.stream().map(t -> t.getToolDefinition().name()).toList());
    }

    /** Backward-compatibility constructor for unit tests */
    public AiOrchestrationService(
            List<ToolCallback> mcpTools,
            ApplicationContext applicationContext,
            ChatMemoryService chatMemoryService,
            CopilotSdkService copilotSdkService,
            String configuredModel,
            String defaultGitOrg,
            Resource systemPromptResource) {
        this(mcpTools, applicationContext, chatMemoryService,
                copilotSdkService != null ? List.of(copilotSdkService) : Collections.emptyList(),
                "copilot", configuredModel, defaultGitOrg, systemPromptResource);
    }

    /**
     * Process the prompt based on mode and MCP tool availability.
     * Maintains conversation history per session for contextual responses.
     *
     * Mode and tool routing:
     * - "chat": Uses Copilot SDK (fast, no tools) for informational questions
     * - "agent": Uses Copilot SDK with native MCP tool support for complex tasks
     *
     * Copilot SDK handles both modes exclusively with native tool execution capabilities.
     */
    public PromptResponse processPrompt(PromptRequest request) {
        String mode = resolveMode(request.getMode());

        // Resolve or create session
        String sessionId = resolveSessionId(request.getSessionId());

        if (sessionAuthService != null) {
            sessionAuthService.setCurrentSessionId(sessionId);
        }
        log.info("Processing prompt for session [{}] mode: {}, tools available: {}",
            sessionId, mode, currentMcpTools().size());

        // Store user message in session memory
        chatMemoryService.addUserMessage(sessionId, request.getPrompt());

        // Build conversation context from session history
        List<Message> history = chatMemoryService.getHistory(sessionId);
        String contextualPrompt = buildContextualPrompt(history, request.getPrompt());

        LlmProviderService preliminaryProvider = resolveProvider(request.getProvider());
        String activeModel = preliminaryProvider != null ? preliminaryProvider.getModel() : configuredModel;

        if (isGreeting(request.getPrompt())) {
            String greeting = "Hello, I am EMH AI ASSISTANT. How can I help you today?";
            chatMemoryService.addAssistantMessage(sessionId, greeting);
            return PromptResponse.builder()
                .response(greeting)
                .sessionId(sessionId)
                .provider("EMH AI ASSISTANT")
                .model(activeModel)
                .toolsUsed(Collections.emptyList())
                .toolResults(Collections.emptyList())
                .timestamp(Instant.now())
                .build();
        }

        // Vulnerability requests: counts/reports and the "which release?" question are answered without the model;
        // detail questions and fixes continue below with a resolved scope and the vulnerability tools/skills.
        VulnerabilityChatService.Outcome vulnerability = vulnerabilityChat == null
                ? null : vulnerabilityChat.handle(sessionId, request.getPrompt());
        if (vulnerability != null && vulnerability.answered()) {
            chatMemoryService.addAssistantMessage(sessionId, vulnerability.response());
            return PromptResponse.builder()
                .response(vulnerability.response())
                .sessionId(sessionId)
                .provider("EMH AI ASSISTANT")
                .model(activeModel)
                .toolsUsed(vulnerability.toolsUsed())
                .toolResults(Collections.emptyList())
                .timestamp(Instant.now())
                .build();
        }
        if (vulnerability != null && vulnerability.prompt() != null) {
            request.setPrompt(vulnerability.prompt());
            contextualPrompt = buildContextualPrompt(history, vulnerability.prompt());
        }

        // "next 25" / "page 3" after a Jira list: continue the last search with startAt, no model needed.
        PromptResponse jiraPage = tryJiraPage(sessionId, request);
        if (jiraPage != null) {
            return jiraPage;
        }

        LlmProviderService provider = resolveProvider(request.getProvider());
        if (provider == null || !provider.isAvailable()) {
            String providerName = provider != null ? provider.getProviderName() : (request.getProvider() != null ? request.getProvider() : configuredProvider);
            String error = "LLM provider '" + providerName + "' is not available. Please verify configuration (AI_PROVIDER, ANTIGRAVITY_ENABLED, or COPILOT_SDK_ENABLED).";
            log.error("Session [{}]: {}", sessionId, error);
            return PromptResponse.builder()
                    .response(error)
                    .sessionId(sessionId)
                    .provider(providerName)
                    .model("unknown")
                    .timestamp(Instant.now())
                    .build();
        }

        log.info("Session [{}]: Using LLM Provider [{}] (model: {}, mode: {})",
                sessionId, provider.getProviderName(), provider.getModel(), mode);
        return processWithProvider(provider, mode, sessionId, contextualPrompt, request, vulnerability);
    }

    /**
     * Process the prompt using Server-Sent Events (SSE) and Project Reactor Flux.
     * Streams thinking updates, tool invocation progress, line-by-line text chunks, and final completion.
     */
    public Flux<ServerSentEvent<ChatStreamEvent>> processPromptStream(PromptRequest request) {
        return Flux.<ServerSentEvent<ChatStreamEvent>>create(sink -> {
            sink.onCancel(() -> {
                String sid = request.getSessionId();
                if (sid != null && !sid.isBlank()) {
                    log.info("Session [{}]: SSE stream cancelled by client; stopping active provider processes", sid);
                    clearProviderSession(sid);
                }
            });
            try {
                doProcessPromptStream(request, sink);
                sink.complete();
            } catch (Exception e) {
                log.error("Error in prompt stream: {}", e.getMessage(), e);
                emitEvent(sink, ChatStreamEvent.builder()
                        .type("error")
                        .content(e.getMessage() != null ? e.getMessage() : "Internal error occurred")
                        .timestamp(Instant.now())
                        .build());
                sink.complete();
            }
        }, FluxSink.OverflowStrategy.BUFFER)
        .subscribeOn(Schedulers.boundedElastic());
    }

    private void doProcessPromptStream(PromptRequest request, FluxSink<ServerSentEvent<ChatStreamEvent>> sink) {
        String mode = resolveMode(request.getMode());
        String sessionId = resolveSessionId(request.getSessionId());

        if (sessionAuthService != null) {
            sessionAuthService.setCurrentSessionId(sessionId);
        }

        emitEvent(sink, ChatStreamEvent.builder()
                .type("thinking")
                .content("Initializing conversation session...")
                .sessionId(sessionId)
                .build());

        chatMemoryService.addUserMessage(sessionId, request.getPrompt());
        List<Message> history = chatMemoryService.getHistory(sessionId);
        String contextualPrompt = buildContextualPrompt(history, request.getPrompt());

        LlmProviderService preliminaryProvider = resolveProvider(request.getProvider());
        String activeModel = preliminaryProvider != null ? preliminaryProvider.getModel() : configuredModel;

        if (isGreeting(request.getPrompt())) {
            emitEvent(sink, ChatStreamEvent.builder()
                    .type("thinking")
                    .content("Preparing greeting response...")
                    .sessionId(sessionId)
                    .build());
            String greeting = "Hello, I am EMH AI ASSISTANT. How can I help you today?";
            chatMemoryService.addAssistantMessage(sessionId, greeting);
            emitTextChunks(sink, greeting, sessionId, "EMH AI ASSISTANT", activeModel);

            PromptResponse response = PromptResponse.builder()
                    .response(greeting)
                    .sessionId(sessionId)
                    .provider("EMH AI ASSISTANT")
                    .model(activeModel)
                    .toolsUsed(Collections.emptyList())
                    .toolResults(Collections.emptyList())
                    .timestamp(Instant.now())
                    .build();
            emitEvent(sink, ChatStreamEvent.builder()
                    .type("done")
                    .content("Complete")
                    .sessionId(sessionId)
                    .response(response)
                    .build());
            return;
        }

        VulnerabilityChatService.Outcome vulnerability = vulnerabilityChat == null
                ? null : vulnerabilityChat.handle(sessionId, request.getPrompt());
        if (vulnerability != null && vulnerability.answered()) {
            emitEvent(sink, ChatStreamEvent.builder()
                    .type("thinking")
                    .content("Fetching vulnerability metrics from JFrog Xray...")
                    .sessionId(sessionId)
                    .build());
            chatMemoryService.addAssistantMessage(sessionId, vulnerability.response());
            emitTextChunks(sink, vulnerability.response(), sessionId, "EMH AI ASSISTANT", activeModel);

            PromptResponse response = PromptResponse.builder()
                    .response(vulnerability.response())
                    .sessionId(sessionId)
                    .provider("EMH AI ASSISTANT")
                    .model(activeModel)
                    .toolsUsed(vulnerability.toolsUsed())
                    .toolResults(Collections.emptyList())
                    .timestamp(Instant.now())
                    .build();
            emitEvent(sink, ChatStreamEvent.builder()
                    .type("done")
                    .content("Complete")
                    .sessionId(sessionId)
                    .response(response)
                    .build());
            return;
        }

        if (vulnerability != null && vulnerability.prompt() != null) {
            request.setPrompt(vulnerability.prompt());
            contextualPrompt = buildContextualPrompt(history, vulnerability.prompt());
        }

        PromptResponse jiraPage = tryJiraPage(sessionId, request);
        if (jiraPage != null) {
            emitEvent(sink, ChatStreamEvent.builder()
                    .type("thinking")
                    .content("Loading paginated Jira results...")
                    .sessionId(sessionId)
                    .build());
            emitTextChunks(sink, jiraPage.getResponse(), sessionId, jiraPage.getProvider(), jiraPage.getModel());
            emitEvent(sink, ChatStreamEvent.builder()
                    .type("done")
                    .content("Complete")
                    .sessionId(sessionId)
                    .response(jiraPage)
                    .build());
            return;
        }

        LlmProviderService provider = resolveProvider(request.getProvider());
        if (provider == null || !provider.isAvailable()) {
            String providerName = provider != null ? provider.getProviderName() : (request.getProvider() != null ? request.getProvider() : configuredProvider);
            String error = "LLM provider '" + providerName + "' is not available. Please verify configuration (AI_PROVIDER, ANTIGRAVITY_ENABLED, or COPILOT_SDK_ENABLED).";
            log.error("Session [{}]: {}", sessionId, error);
            PromptResponse response = PromptResponse.builder()
                    .response(error)
                    .sessionId(sessionId)
                    .provider(providerName)
                    .model("unknown")
                    .timestamp(Instant.now())
                    .build();
            emitEvent(sink, ChatStreamEvent.builder()
                    .type("error")
                    .content(error)
                    .sessionId(sessionId)
                    .response(response)
                    .build());
            return;
        }

        emitEvent(sink, ChatStreamEvent.builder()
                .type("thinking")
                .content("Routing request to " + provider.getDisplayName() + " (" + provider.getModel() + ")...")
                .sessionId(sessionId)
                .provider(provider.getProviderName())
                .model(provider.getModel())
                .build());

        processStreamWithProvider(provider, mode, sessionId, contextualPrompt, request, vulnerability, sink);
    }

    private void processStreamWithProvider(LlmProviderService provider, String mode, String sessionId, String contextualPrompt, PromptRequest request,
                                           VulnerabilityChatService.Outcome vulnerability, FluxSink<ServerSentEvent<ChatStreamEvent>> sink) {
        try {
            boolean paginationRequest = isPaginationRequest(request.getPrompt());
            String previousIntent = sessionId != null ? lastIntentBySession.get(sessionId) : null;

            boolean jiraIntent = isJiraRequest(request.getPrompt())
                    || (paginationRequest && (isJiraRequest(contextualPrompt) || "jira".equals(previousIntent) || containsAny(request.getPrompt().toLowerCase(), "jira")));
            boolean gitIntent = isGitRequest(request.getPrompt())
                    || (paginationRequest && (isGitRequest(contextualPrompt) || "git".equals(previousIntent)));
            boolean confluenceIntent = isConfluenceRequest(request.getPrompt())
                    || isKnowledgeRequest(request.getPrompt())
                    || (paginationRequest && (isConfluenceRequest(contextualPrompt) || "confluence".equals(previousIntent)));

            boolean kafkaIntent = isKafkaRequest(request.getPrompt())
                    || (paginationRequest && "kafka".equals(previousIntent));
            boolean serviceNowIntent = isServiceNowRequest(request.getPrompt())
                    || (paginationRequest && "servicenow".equals(previousIntent));
            boolean reportIntent = isReportOrPptRequest(request.getPrompt())
                    || (paginationRequest && "report".equals(previousIntent));
            boolean isOutlookFollowUp = containsAny(request.getPrompt().toLowerCase(Locale.ROOT), "summarize", "summary", "brief", "show them", "list them", "details");
            boolean outlookIntent = isOutlookRequest(request.getPrompt())
                    || ("outlook".equals(previousIntent) && (paginationRequest || isOutlookFollowUp || isOutlookRequest(contextualPrompt)));

            if (paginationRequest && !jiraIntent && !gitIntent && !confluenceIntent && !kafkaIntent && !serviceNowIntent && !reportIntent && !outlookIntent) {
                if ("git".equals(previousIntent)) {
                    gitIntent = true;
                } else if ("confluence".equals(previousIntent)) {
                    confluenceIntent = true;
                } else if ("kafka".equals(previousIntent)) {
                    kafkaIntent = true;
                } else if ("servicenow".equals(previousIntent)) {
                    serviceNowIntent = true;
                } else if ("report".equals(previousIntent)) {
                    reportIntent = true;
                } else if ("outlook".equals(previousIntent)) {
                    outlookIntent = true;
                } else {
                    jiraIntent = true;
                }
            }

            List<Message> history = chatMemoryService.getHistory(sessionId);
            boolean confirmedApproval = isConfirmChange(request.getPrompt(), history);

            String ticketKey = extractIssueKey(request.getPrompt());
            boolean changeIntent = isChangeRequest(request.getPrompt());
            if (ticketKey == null && (changeIntent || confirmedApproval)) {
                ticketKey = extractIssueKey(contextualPrompt);
            }

            boolean vulnTask = vulnerability != null && vulnerability.vulnerabilityTask();
            String vulnMode = !vulnTask ? null : (vulnerability.fix() ? "fix" : "report");
            if (vulnTask) {
                jiraIntent = false;
                gitIntent = false;
                confluenceIntent = false;
                kafkaIntent = false;
                serviceNowIntent = false;
                reportIntent = false;
                outlookIntent = false;
                ticketKey = null;
                changeIntent = false;
            }

            boolean directTicketOrChange = ticketKey != null || changeIntent || confirmedApproval || vulnTask;
            if (changeIntent || confirmedApproval) {
                gitIntent = true;
            }

            boolean isExplicitChat = "chat".equalsIgnoreCase(mode);
            boolean requestedToolIntent = jiraIntent || gitIntent || confluenceIntent || kafkaIntent || serviceNowIntent || reportIntent || outlookIntent || directTicketOrChange;
            boolean isAgentMode = !isExplicitChat && ("agent".equalsIgnoreCase(mode) || requestedToolIntent || paginationRequest);

            if (isAgentMode) {
                if (confirmedApproval) {
                    emitEvent(sink, ChatStreamEvent.builder()
                            .type("thinking")
                            .content("Change proposal accepted by user. Executing approved changes, clean test, spring-boot:run, and PR creation (skipping rework)...")
                            .sessionId(sessionId)
                            .build());
                } else if (jiraIntent || ticketKey != null) {
                    lastIntentBySession.put(sessionId, "jira");
                    emitEvent(sink, ChatStreamEvent.builder()
                            .type("thinking")
                            .content("Identified Jira intent. Planning Jira MCP tool calls...")
                            .sessionId(sessionId)
                            .build());
                } else if (gitIntent) {
                    lastIntentBySession.put(sessionId, "git");
                    emitEvent(sink, ChatStreamEvent.builder()
                            .type("thinking")
                            .content("Identified Git/PR intent. Planning GitHub MCP tool calls...")
                            .sessionId(sessionId)
                            .build());
                } else if (reportIntent) {
                    lastIntentBySession.put(sessionId, "report");
                    emitEvent(sink, ChatStreamEvent.builder()
                            .type("thinking")
                            .content("Identified Executive Presentation / Report intent. Planning PowerPoint generation across Jira, ServiceNow, Outlook intelligence, and JFrog...")
                            .sessionId(sessionId)
                            .build());
                } else if (outlookIntent) {
                    lastIntentBySession.put(sessionId, "outlook");
                    emitEvent(sink, ChatStreamEvent.builder()
                            .type("thinking")
                            .content("Identified Outlook email intelligence intent. Scanning emails and major issue escalations...")
                            .sessionId(sessionId)
                            .build());
                } else if (kafkaIntent) {
                    lastIntentBySession.put(sessionId, "kafka");
                    emitEvent(sink, ChatStreamEvent.builder()
                            .type("thinking")
                            .content("Identified Kafka Connect intent. Planning Kafka connector operations and task restart...")
                            .sessionId(sessionId)
                            .build());
                } else if (serviceNowIntent) {
                    lastIntentBySession.put(sessionId, "servicenow");
                    emitEvent(sink, ChatStreamEvent.builder()
                            .type("thinking")
                            .content("Identified ServiceNow intent. Planning incident and service request tool calls for EI Services Global L2...")
                            .sessionId(sessionId)
                            .build());
                } else if (confluenceIntent) {
                    lastIntentBySession.put(sessionId, "confluence");
                    emitEvent(sink, ChatStreamEvent.builder()
                            .type("thinking")
                            .content("Identified Confluence / documentation intent...")
                            .sessionId(sessionId)
                            .build());
                } else if (vulnTask) {
                    emitEvent(sink, ChatStreamEvent.builder()
                            .type("thinking")
                            .content("Starting JFrog Xray vulnerability workflow...")
                            .sessionId(sessionId)
                            .build());
                }
            }

            List<ToolCallback> selectedTools = Collections.emptyList();
            List<String> selectedToolNames = Collections.emptyList();
            Map<String, ToolCallback> toolsByName = new HashMap<>();

            if (isAgentMode) {
                List<ToolCallback> availableTools = currentMcpTools();
                if (availableTools.isEmpty()) {
                    String message = "Agent mode has no MCP tools available. Verify MCP_ENABLED=true and MCP server startup.";
                    chatMemoryService.addAssistantMessage(sessionId, message);
                    emitTextChunks(sink, message, sessionId, provider.getProviderName(), provider.getModel());
                    PromptResponse response = PromptResponse.builder()
                            .response(message)
                            .sessionId(sessionId)
                            .provider(provider.getProviderName())
                            .model(provider.getModel())
                            .toolsUsed(Collections.emptyList())
                            .toolResults(Collections.emptyList())
                            .timestamp(Instant.now())
                            .build();
                    emitEvent(sink, ChatStreamEvent.builder()
                            .type("done")
                            .content("Complete")
                            .sessionId(sessionId)
                            .response(response)
                            .build());
                    return;
                }

                selectedTools = confirmedApproval
                        ? resolveApprovedChangeTools()
                        : (vulnTask
                                ? resolveVulnerabilityTools(confirmedApproval)
                                : resolveAgentToolsByIntent(request.getPrompt(), request.getEnabledTools(), confirmedApproval));
                if (selectedTools.isEmpty() && requestedToolIntent) {
                    selectedTools = availableTools;
                }

                selectedToolNames = selectedTools.stream()
                        .map(tool -> tool.getToolDefinition().name())
                        .toList();

                for (ToolCallback tool : selectedTools) {
                    toolsByName.put(tool.getToolDefinition().name(), tool);
                }

                if (!selectedTools.isEmpty()) {
                    String armMessage = confirmedApproval
                            ? "Armed agent with " + selectedTools.size() + " execution tools for approved changes (skipping rework)."
                            : "Armed agent with " + selectedTools.size() + " MCP tools.";
                    emitEvent(sink, ChatStreamEvent.builder()
                            .type("thinking")
                            .content(armMessage)
                            .sessionId(sessionId)
                            .build());
                } else {
                    emitEvent(sink, ChatStreamEvent.builder()
                            .type("thinking")
                            .content("No external enterprise tools needed for this query. Responding directly...")
                            .sessionId(sessionId)
                            .build());
                }
            }

            List<String> toolsUsed = new ArrayList<>();
            List<Map<String, Object>> toolResults = new ArrayList<>();

            String skillContext = (isAgentMode && !selectedTools.isEmpty())
                    ? (confirmedApproval ? "" : buildSkillContext(jiraIntent, gitIntent, changeIntent, ticketKey != null, vulnMode, kafkaIntent, serviceNowIntent, reportIntent, outlookIntent))
                    : "";

            String prefetchedIssue = "";
            if (isAgentMode && !selectedTools.isEmpty() && ticketKey != null && !confirmedApproval) {
                emitEvent(sink, ChatStreamEvent.builder()
                        .type("thinking")
                        .content("Prefetching Jira ticket " + ticketKey + "...")
                        .sessionId(sessionId)
                        .build());
                prefetchedIssue = prefetchJiraIssue(ticketKey, toolsByName, toolsUsed, toolResults);
                emitEvent(sink, ChatStreamEvent.builder()
                        .type("thinking")
                        .content("Prefetched ticket " + ticketKey + " details.")
                        .sessionId(sessionId)
                        .build());
            }

            String copilotPrompt = (isAgentMode && !selectedTools.isEmpty())
                    ? buildAgentPromptWithToolDirective(sessionId, buildCopilotPromptWithSystem(contextualPrompt), request.getPrompt(),
                            selectedToolNames, skillContext, prefetchedIssue, vulnMode, confirmedApproval)
                    : buildChatPrompt(contextualPrompt);

            emitEvent(sink, ChatStreamEvent.builder()
                    .type("thinking")
                    .content("Consulting " + provider.getDisplayName() + " (" + provider.getModel() + ")...")
                    .sessionId(sessionId)
                    .build());

            String responseText = sendWithProgress(provider, sessionId, copilotPrompt, selectedTools,
                    "Consulting " + provider.getDisplayName(), sink);

            if (!toolsByName.isEmpty()) {
                List<ToolInvocation> detectedTools = detectToolInvocations(responseText, toolsByName.keySet());

                int agentRound = 0;
                int maxAgentRounds = !isAgentMode ? 1
                        : "fix".equals(vulnMode) ? MAX_FIX_ROUNDS
                        : "report".equals(vulnMode) ? MAX_VULN_REPORT_ROUNDS : MAX_AGENT_ROUNDS;
                boolean agentTimedOut = false;
                List<ToolInvocation> pendingTools = detectedTools;
                List<String[]> agentSteps = new ArrayList<>();

                while (!pendingTools.isEmpty() && agentRound < maxAgentRounds) {
                    agentRound++;
                    emitEvent(sink, ChatStreamEvent.builder()
                            .type("thinking")
                            .content("Round " + agentRound + ": executing " + pendingTools.size() + " tool call(s)...")
                            .sessionId(sessionId)
                            .build());

                    StringBuilder executionResults = new StringBuilder();
                    boolean batchFailed = false;
                    int executedInRound = 0;
                    for (ToolInvocation invocation : pendingTools) {
                        String callText = abbreviate(invocation.toolName + "(" + invocation.parameters + ")", 700);
                        if (batchFailed || executedInRound >= MAX_TOOL_CALLS_PER_ROUND) {
                            String skipped = batchFailed
                                    ? "SKIPPED: an earlier call in this step failed. Fix that first, then repeat this call."
                                    : "SKIPPED: at most " + MAX_TOOL_CALLS_PER_ROUND + " tool calls run per step. Repeat this call in the next step if it is still needed.";
                            agentSteps.add(new String[]{callText, skipped});
                            executionResults.append("\n[Tool Skipped: ").append(invocation.toolName).append("]\n").append(skipped);
                            continue;
                        }
                        executedInRound++;
                        emitEvent(sink, ChatStreamEvent.builder()
                                .type("thinking")
                                .content("Invoking MCP tool: " + invocation.toolName + "...")
                                .toolName(invocation.toolName)
                                .sessionId(sessionId)
                                .build());
                        emitEvent(sink, ChatStreamEvent.builder()
                                .type("tool_call")
                                .content(invocation.parameters)
                                .toolName(invocation.toolName)
                                .sessionId(sessionId)
                                .build());

                        try {
                            trackJiraSearch(sessionId, invocation);
                            String result = executeToolInvocation(invocation, toolsByName);
                            boolean failedResult = isFailedToolResult(result);
                            if (!failedResult) {
                                updateSessionRemediationState(sessionId, invocation, result);
                            }
                            toolsUsed.add(invocation.toolName);
                            agentSteps.add(new String[]{callText, result});
                            toolResults.add(Map.of(
                                    "tool", invocation.toolName,
                                    "status", failedResult ? "error" : "success",
                                    "result", result
                            ));
                            if (failedResult) {
                                batchFailed = true;
                            }
                            executionResults.append("\n[Tool Result: ").append(invocation.toolName).append("]\n").append(result);

                            emitEvent(sink, ChatStreamEvent.builder()
                                    .type("tool_result")
                                    .content(abbreviate(result != null ? result : "(no output)", 500))
                                    .toolName(invocation.toolName)
                                    .sessionId(sessionId)
                                    .toolPayload(Map.of("status", failedResult ? "error" : "success"))
                                    .build());
                            emitEvent(sink, ChatStreamEvent.builder()
                                    .type("thinking")
                                    .content("Tool " + invocation.toolName + " finished (" + (failedResult ? "error" : "success") + ").")
                                    .toolName(invocation.toolName)
                                    .sessionId(sessionId)
                                    .build());
                        } catch (Exception e) {
                            toolResults.add(Map.of(
                                    "tool", invocation.toolName,
                                    "status", "error",
                                    "error", String.valueOf(e.getMessage())
                            ));
                            executionResults.append("\n[Tool Error: ").append(invocation.toolName).append("]\n")
                                    .append(e.getMessage());
                            agentSteps.add(new String[]{callText, "ERROR: " + e.getMessage()});
                            batchFailed = true;

                            emitEvent(sink, ChatStreamEvent.builder()
                                    .type("tool_result")
                                    .content("ERROR: " + e.getMessage())
                                    .toolName(invocation.toolName)
                                    .sessionId(sessionId)
                                    .toolPayload(Map.of("status", "error"))
                                    .build());
                        }
                    }

                    if (!toolResults.isEmpty()) {
                        String refiningPrompt = isAgentMode
                                ? buildAgentContinuationPrompt(sessionId, contextualPrompt, skillContext, selectedToolNames, agentSteps, agentRound, maxAgentRounds)
                                : buildRefiningPrompt(request.getPrompt(), executionResults.toString());
                        emitEvent(sink, ChatStreamEvent.builder()
                                .type("thinking")
                                .content("Synthesizing tool findings with " + provider.getDisplayName() + "...")
                                .sessionId(sessionId)
                                .build());
                        try {
                            responseText = sendWithProgress(provider, sessionId, refiningPrompt,
                                    isAgentMode ? selectedTools : Collections.<ToolCallback>emptyList(),
                                    "Synthesizing tool findings with " + provider.getDisplayName(), sink);
                        } catch (Exception refineEx) {
                            if (refineEx.getMessage() != null && refineEx.getMessage().contains("timed out")) {
                                responseText = "Tool execution completed successfully.\n\nResults:\n" + executionResults.toString() +
                                        "\n\nNote: Response refinement timed out; showing raw tool results.";
                                agentTimedOut = true;
                            } else {
                                throw refineEx;
                            }
                        }
                    }

                    pendingTools = agentTimedOut
                            ? Collections.<ToolInvocation>emptyList()
                            : detectToolInvocations(responseText, toolsByName.keySet());
                }

                if (isAgentMode && !pendingTools.isEmpty()) {
                    responseText = responseText + "\n\n_Stopped after " + maxAgentRounds
                            + " tool steps. Ask me to continue if the task is not finished._";
                }

                // Deterministic Jira fallback check
                if (jiraIntent && !directTicketOrChange) {
                    String lowerResponse = responseText == null ? "" : responseText.toLowerCase();
                    String toolResultText = toolResults.isEmpty() ? "" :
                            toolResults.stream()
                                    .map(r -> r.get("result") != null ? r.get("result").toString() : "")
                                    .collect(Collectors.joining(" "));
                    String lowerToolResult = toolResultText.toLowerCase();

                    boolean looksLikeNoAccess = containsAny(lowerResponse,
                            "don't have access", "do not have access", "no integration", "not connected", "can't access jira", "cannot access jira");
                    boolean looksLikeFieldError = containsAny(lowerToolResult,
                            "isn't a valid component", "is not a valid component", "not a valid component", "unknown field", "invalid field");
                    boolean noSuccessResults = toolResults.stream().noneMatch(result -> "success".equals(result.get("status")));

                    if (looksLikeNoAccess || looksLikeFieldError || (noSuccessResults && detectedTools.isEmpty())) {
                        Optional<Map.Entry<String, ToolCallback>> jiraToolEntry = toolsByName.entrySet().stream()
                                .filter(entry -> entry.getKey().toLowerCase().contains("jira")
                                        && entry.getKey().toLowerCase().contains("search"))
                                .findFirst();

                        if (jiraToolEntry.isPresent()) {
                            emitEvent(sink, ChatStreamEvent.builder()
                                    .type("thinking")
                                    .content("Executing deterministic Jira fallback search...")
                                    .sessionId(sessionId)
                                    .build());
                            String jql = buildJiraJqlFromPrompt(request.getPrompt());
                            String fallbackToolName = jiraToolEntry.get().getKey();
                            List<String> jqlCandidates = buildJiraJqlCandidates(jql);
                            int fallbackStart = Math.max(0, JiraPaging.requestedPage(request.getPrompt()) - 1) * JIRA_PAGE_SIZE;
                            List<String> fallbackParamsCandidates = jqlCandidates.stream()
                                    .map(candidate -> "{\"jql\":\"" + escapeJson(JiraPaging.withStableOrder(candidate)) + "\""
                                            + ",\"startAt\":" + fallbackStart + ",\"maxResults\":" + JIRA_PAGE_SIZE + "}")
                                    .toList();

                            try {
                                String jiraResult = tryToolPayloads(jiraToolEntry.get().getValue(), fallbackParamsCandidates);
                                jiraLastSearch.put(sessionId, new JiraPaging.State(JiraPaging.withStableOrder(jql), fallbackStart, JIRA_PAGE_SIZE));
                                toolsUsed.add(fallbackToolName);
                                toolResults.add(Map.of(
                                        "tool", fallbackToolName,
                                        "status", "success",
                                        "result", jiraResult != null ? jiraResult : "(no result)",
                                        "fallback", true
                                ));

                                String executionResults = "\n[Tool Result: " + fallbackToolName + "]\n" + (jiraResult != null ? jiraResult : "(no result)");
                                String refiningPrompt = buildRefiningPrompt(request.getPrompt(), executionResults);
                                try {
                                    responseText = sendWithProgress(provider, sessionId, refiningPrompt, Collections.emptyList(),
                                            "Refining Jira results with " + provider.getDisplayName(), sink);
                                } catch (Exception refineEx) {
                                    if (refineEx.getMessage() != null && refineEx.getMessage().contains("timed out")) {
                                        responseText = "Jira search completed with results:\n\n" + (jiraResult != null ? jiraResult : "(no result)") +
                                                "\n\nNote: Response refinement timed out; showing raw tool results.";
                                    } else {
                                        throw refineEx;
                                    }
                                }
                            } catch (Exception fallbackEx) {
                                toolResults.add(Map.of(
                                        "tool", fallbackToolName,
                                        "status", "error",
                                        "error", fallbackEx.getMessage(),
                                        "fallback", true
                                ));
                                responseText = "Jira tool execution failed: " + fallbackEx.getMessage() +
                                        ". Verify Jira MCP connectivity and credentials.";
                            }
                        }
                    }
                }

                // Deterministic Git fallback check
                if (gitIntent && !directTicketOrChange) {
                    boolean noSuccessfulGitTool = toolResults.stream()
                            .filter(result -> result.get("tool") != null && result.get("tool").toString().toLowerCase().contains("git"))
                            .noneMatch(result -> "success".equals(result.get("status")));
                    String lowerResponse = responseText == null ? "" : responseText.toLowerCase();
                    boolean looksLikeNoAccess = containsAny(lowerResponse,
                            "don't have access", "do not have access", "no integration", "not connected", "github copilot cli", "gh",
                            "not a git repository", "no remote to query", "can't fetch real pr data", "cannot fetch real pr data");

                    if (looksLikeNoAccess || detectedTools.isEmpty() || noSuccessfulGitTool) {
                        Optional<Map.Entry<String, ToolCallback>> listPrToolEntry = toolsByName.entrySet().stream()
                                .filter(entry -> entry.getKey().toLowerCase().contains("git")
                                        && entry.getKey().toLowerCase().contains("list_pull_requests"))
                                .findFirst();
                        Optional<String[]> ownerRepo = extractOwnerRepoFromPrompt(request.getPrompt());
                        Optional<String> repoOnly = extractRepoNameFromPrompt(request.getPrompt());

                        if (listPrToolEntry.isPresent() && (ownerRepo.isPresent() || repoOnly.isPresent())) {
                            emitEvent(sink, ChatStreamEvent.builder()
                                    .type("thinking")
                                    .content("Executing deterministic Git PR search fallback...")
                                    .sessionId(sessionId)
                                    .build());
                            String owner = ownerRepo.map(value -> value[0]).orElseGet(() -> isBlank(defaultGitOrg) ? "ifw-microservices" : defaultGitOrg);
                            String repo = ownerRepo.map(value -> value[1]).orElseGet(repoOnly::get);
                            String fallbackToolName = listPrToolEntry.get().getKey();
                            List<String> payloadCandidates = new ArrayList<>();
                            if (!isBlank(owner)) {
                                payloadCandidates.add("{\"owner\":\"" + escapeJson(owner) + "\",\"repo\":\"" + escapeJson(repo) + "\",\"state\":\"open\"}");
                                payloadCandidates.add("{\"repo\":\"" + escapeJson(owner + "/" + repo) + "\",\"state\":\"open\"}");
                                payloadCandidates.add("{\"repository\":\"" + escapeJson(owner + "/" + repo) + "\",\"state\":\"open\"}");
                            }
                            try {
                                String gitResult = tryToolPayloads(listPrToolEntry.get().getValue(), payloadCandidates);
                                toolsUsed.add(fallbackToolName);
                                toolResults.add(Map.of(
                                        "tool", fallbackToolName,
                                        "status", "success",
                                        "result", gitResult != null ? gitResult : "(no result)",
                                        "fallback", true
                                ));
                                String executionResults = "\n[Tool Result: " + fallbackToolName + "]\n" + (gitResult != null ? gitResult : "(no result)");
                                String refiningPrompt = buildRefiningPrompt(request.getPrompt(), executionResults);
                                responseText = sendWithProgress(provider, sessionId, refiningPrompt, Collections.emptyList(),
                                        "Refining Git results with " + provider.getDisplayName(), sink);
                            } catch (Exception fallbackEx) {
                                toolResults.add(Map.of(
                                        "tool", fallbackToolName,
                                        "status", "error",
                                        "error", fallbackEx.getMessage(),
                                        "fallback", true
                                ));
                                responseText = "Git PR search fallback failed: " + fallbackEx.getMessage() +
                                        ". If possible, include repository as owner/repo in your prompt.";
                            }
                        }
                    }
                }

                // Deterministic Outlook email intelligence fallback check
                if (outlookIntent) {
                    boolean noSuccessfulOutlookTool = toolResults.stream()
                            .filter(result -> result.get("tool") != null && result.get("tool").toString().toLowerCase().contains("outlook"))
                            .noneMatch(result -> "success".equals(result.get("status")));
                    String lowerResponse = responseText == null ? "" : responseText.toLowerCase();
                    boolean looksLikeNoAccess = containsAny(lowerResponse,
                            "don't have access", "do not have access", "no integration", "not connected",
                            "cannot access", "can't access", "don't have direct access", "unable to check emails");

                    if (looksLikeNoAccess || detectedTools.isEmpty() || noSuccessfulOutlookTool) {
                        String pLower = request.getPrompt().toLowerCase(Locale.ROOT);
                        boolean wantsSummary = containsAny(pLower, "summarize", "summary", "brief");
                        String folder = extractFolderFromPrompt(request.getPrompt());
                        int count = extractCountFromPrompt(request.getPrompt(), 5);
                        int days = extractDaysFromPrompt(request.getPrompt(), 0);

                        Optional<Map.Entry<String, ToolCallback>> outlookToolEntry = Optional.empty();
                        if (wantsSummary) {
                            outlookToolEntry = toolsByName.entrySet().stream()
                                    .filter(entry -> entry.getKey().equalsIgnoreCase("outlook_summarizeEmails")
                                            || entry.getKey().toLowerCase().contains("summarizeemails"))
                                    .findFirst();
                        } else {
                            outlookToolEntry = toolsByName.entrySet().stream()
                                    .filter(entry -> entry.getKey().equalsIgnoreCase("outlook_fetchEmails")
                                            || entry.getKey().toLowerCase().contains("fetchemails"))
                                    .findFirst();
                        }
                        if (outlookToolEntry.isEmpty()) {
                            outlookToolEntry = toolsByName.entrySet().stream()
                                    .filter(entry -> entry.getKey().equalsIgnoreCase("outlook_summarizeMajorIssues")
                                            || entry.getKey().toLowerCase().contains("summarizemajorissues"))
                                    .findFirst();
                        }
                        if (outlookToolEntry.isEmpty()) {
                            outlookToolEntry = toolsByName.entrySet().stream()
                                    .filter(entry -> entry.getKey().toLowerCase().contains("outlook"))
                                    .findFirst();
                        }

                        if (outlookToolEntry.isPresent()) {
                            emitEvent(sink, ChatStreamEvent.builder()
                                    .type("thinking")
                                    .content("Executing deterministic Outlook email intelligence fallback (" + folder + ", limit " + count + ")...")
                                    .sessionId(sessionId)
                                    .build());
                            String fallbackToolName = outlookToolEntry.get().getKey();
                            List<String> payloadCandidates = new ArrayList<>();
                            if (days > 0) {
                                payloadCandidates.add(String.format("{\"folder\":\"%s\",\"limit\":%d,\"days\":%d}", folder, count, days));
                                payloadCandidates.add(String.format("{\"days\":%d}", days));
                            }
                            payloadCandidates.add(String.format("{\"folder\":\"%s\",\"limit\":%d}", folder, count));
                            payloadCandidates.add(String.format("{\"limit\":%d}", count));
                            payloadCandidates.add(String.format("{\"folder\":\"%s\"}", folder));
                            payloadCandidates.add("{}");
                            try {
                                String outlookResult = tryToolPayloads(outlookToolEntry.get().getValue(), payloadCandidates);
                                toolsUsed.add(fallbackToolName);
                                toolResults.add(Map.of(
                                        "tool", fallbackToolName,
                                        "status", "success",
                                        "result", outlookResult != null ? outlookResult : "(no result)",
                                        "fallback", true
                                ));
                                String executionResults = "\n[Tool Result: " + fallbackToolName + "]\n" + (outlookResult != null ? outlookResult : "(no result)");
                                String refiningPrompt = buildRefiningPrompt(request.getPrompt(), executionResults);
                                responseText = sendWithProgress(provider, sessionId, refiningPrompt, Collections.emptyList(),
                                        "Summarizing email intelligence with " + provider.getDisplayName(), sink);
                            } catch (Exception fallbackEx) {
                                toolResults.add(Map.of(
                                        "tool", fallbackToolName,
                                        "status", "error",
                                        "error", fallbackEx.getMessage(),
                                        "fallback", true
                                ));
                                responseText = "Outlook email scanning fallback failed: " + fallbackEx.getMessage() +
                                        ". Verify Outlook integration connectivity.";
                            }
                        }
                    }
                }

                // Deterministic Executive Presentation / PPT fallback check
                if (reportIntent) {
                    boolean noSuccessfulReportTool = toolResults.stream()
                            .filter(result -> result.get("tool") != null && result.get("tool").toString().toLowerCase().contains("report"))
                            .noneMatch(result -> "success".equals(result.get("status")));
                    String lowerResponse = responseText == null ? "" : responseText.toLowerCase();
                    boolean looksLikeNoAccess = containsAny(lowerResponse,
                            "don't have access", "do not have access", "cannot generate ppt", "can't generate ppt", "cannot create ppt", "can't create ppt");

                    if (looksLikeNoAccess || detectedTools.isEmpty() || noSuccessfulReportTool) {
                        Optional<Map.Entry<String, ToolCallback>> reportToolEntry = toolsByName.entrySet().stream()
                                .filter(entry -> entry.getKey().equalsIgnoreCase("report_generateExecutivePpt")
                                        || entry.getKey().toLowerCase().contains("executiveppt")
                                        || entry.getKey().toLowerCase().contains("report_"))
                                .findFirst();

                        if (reportToolEntry.isPresent()) {
                            emitEvent(sink, ChatStreamEvent.builder()
                                    .type("thinking")
                                    .content("Executing deterministic Executive PowerPoint presentation generation...")
                                    .sessionId(sessionId)
                                    .build());
                            int days = extractDaysFromPrompt(request.getPrompt(), 7);
                            String fallbackToolName = reportToolEntry.get().getKey();
                            List<String> payloadCandidates = List.of(
                                    "{\"days\":" + days + ",\"title\":\"EMH Operations & Executive Performance Report\"}",
                                    "{\"days\":" + days + "}",
                                    "{}"
                            );
                            try {
                                String reportResult = tryToolPayloads(reportToolEntry.get().getValue(), payloadCandidates);
                                toolsUsed.add(fallbackToolName);
                                toolResults.add(Map.of(
                                        "tool", fallbackToolName,
                                        "status", "success",
                                        "result", reportResult != null ? reportResult : "(no result)",
                                        "fallback", true
                                ));
                                String executionResults = "\n[Tool Result: " + fallbackToolName + "]\n" + (reportResult != null ? reportResult : "(no result)");
                                String refiningPrompt = buildRefiningPrompt(request.getPrompt(), executionResults);
                                responseText = sendWithProgress(provider, sessionId, refiningPrompt, Collections.emptyList(),
                                        "Formatting executive presentation report with " + provider.getDisplayName(), sink);
                            } catch (Exception fallbackEx) {
                                toolResults.add(Map.of(
                                        "tool", fallbackToolName,
                                        "status", "error",
                                        "error", fallbackEx.getMessage(),
                                        "fallback", true
                                ));
                                responseText = "Executive PPT generation failed: " + fallbackEx.getMessage();
                            }
                        }
                    }
                }
            }

            // Stream response lines to the UI
            emitEvent(sink, ChatStreamEvent.builder()
                    .type("thinking")
                    .content("Delivering response...")
                    .sessionId(sessionId)
                    .build());

            emitTextChunks(sink, responseText, sessionId, provider.getProviderName(), provider.getModel());

            chatMemoryService.addAssistantMessage(sessionId, responseText);

            PromptResponse promptResponse = PromptResponse.builder()
                    .response(responseText)
                    .sessionId(sessionId)
                    .provider(provider.getProviderName())
                    .model(provider.getModel())
                    .toolsUsed(toolsUsed)
                    .toolResults(toolResults)
                    .timestamp(Instant.now())
                    .build();

            emitEvent(sink, ChatStreamEvent.builder()
                    .type("done")
                    .content("Complete")
                    .sessionId(sessionId)
                    .provider(provider.getProviderName())
                    .model(provider.getModel())
                    .toolsUsed(toolsUsed)
                    .response(promptResponse)
                    .build());

        } catch (Exception e) {
            log.error("Error processing stream for session [{}] with provider [{}]: {}", sessionId, provider.getProviderName(), e.getMessage(), e);
            String errorMsg = "Provider error (" + provider.getProviderName() + "): " + e.getMessage();
            emitTextChunks(sink, errorMsg, sessionId, provider.getProviderName(), provider.getModel());
            PromptResponse response = PromptResponse.builder()
                    .response(errorMsg)
                    .sessionId(sessionId)
                    .provider(provider.getProviderName())
                    .model(provider != null ? provider.getModel() : "unknown")
                    .timestamp(Instant.now())
                    .build();
            emitEvent(sink, ChatStreamEvent.builder()
                    .type("error")
                    .content(errorMsg)
                    .sessionId(sessionId)
                    .response(response)
                    .build());
        }
    }

    private void emitEvent(FluxSink<ServerSentEvent<ChatStreamEvent>> sink, ChatStreamEvent event) {
        if (sink == null || sink.isCancelled()) {
            return;
        }
        sink.next(ServerSentEvent.<ChatStreamEvent>builder()
                .event(event.getType())
                .data(event)
                .build());
    }

    static List<String> breakIntoStreamingChunks(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> chunks = new ArrayList<>();
        String[] lines = text.split("(?<=\\n)");
        for (String line : lines) {
            if (line.length() <= 80) {
                chunks.add(line);
            } else {
                String[] words = line.split("(?<=\\s+)");
                StringBuilder currentChunk = new StringBuilder();
                for (String word : words) {
                    currentChunk.append(word);
                    if (currentChunk.length() >= 35) {
                        chunks.add(currentChunk.toString());
                        currentChunk.setLength(0);
                    }
                }
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                }
            }
        }
        return chunks;
    }

    private void emitTextChunks(FluxSink<ServerSentEvent<ChatStreamEvent>> sink, String text, String sessionId, String provider, String model) {
        if (text == null || text.isEmpty() || sink == null || sink.isCancelled()) {
            return;
        }
        List<String> chunks = breakIntoStreamingChunks(text);
        for (String chunk : chunks) {
            if (sink.isCancelled()) {
                return;
            }
            emitEvent(sink, ChatStreamEvent.builder()
                    .type("chunk")
                    .content(chunk)
                    .sessionId(sessionId)
                    .provider(provider)
                    .model(model)
                    .build());
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String sendWithProgress(LlmProviderService provider, String sessionId, String prompt,
                                    List<ToolCallback> tools, String statusDescription,
                                    FluxSink<ServerSentEvent<ChatStreamEvent>> sink) throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "flux-heartbeat");
            t.setDaemon(true);
            return t;
        });
        AtomicInteger elapsed = new AtomicInteger(0);
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> {
            int sec = elapsed.addAndGet(2);
            if (sink != null && !sink.isCancelled()) {
                String dynamicMessage;
                if (sec < 25) {
                    dynamicMessage = statusDescription + "... (" + sec + "s)";
                } else if (sec < 60) {
                    dynamicMessage = "Still working... Analyzing requirements & planning code changes (" + sec + "s)";
                } else if (sec < 120) {
                    dynamicMessage = "It's taking a while... Generating code changes & unified diffs (" + sec + "s)";
                } else {
                    dynamicMessage = "Complex task in progress... Finalizing patch and verification (" + sec + "s)";
                }

                emitEvent(sink, ChatStreamEvent.builder()
                        .type("thinking")
                        .content(dynamicMessage)
                        .sessionId(sessionId)
                        .provider(provider != null ? provider.getProviderName() : null)
                        .model(provider != null ? provider.getModel() : null)
                        .build());
            }
        }, 2, 2, TimeUnit.SECONDS);

        try {
            return provider.sendAndWait(sessionId, prompt, tools);
        } finally {
            heartbeat.cancel(true);
            scheduler.shutdownNow();
        }
    }

    private PromptResponse processWithProvider(LlmProviderService provider, String mode, String sessionId, String contextualPrompt, PromptRequest request,
                                               VulnerabilityChatService.Outcome vulnerability) {
        try {
            if (provider == null || !provider.isAvailable()) {
                String error = "Provider is not available. Check provider configuration.";
                log.warn("Session [{}]: {}", sessionId, error);
                return PromptResponse.builder()
                        .response(error)
                        .sessionId(sessionId)
                        .provider(provider != null ? provider.getProviderName() : "unknown")
                        .model(provider != null ? provider.getModel() : "unknown")
                        .timestamp(Instant.now())
                        .build();
            }

            boolean paginationRequest = isPaginationRequest(request.getPrompt());
            String previousIntent = sessionId != null ? lastIntentBySession.get(sessionId) : null;

            boolean jiraIntent = isJiraRequest(request.getPrompt())
                    || (paginationRequest && (isJiraRequest(contextualPrompt) || "jira".equals(previousIntent) || containsAny(request.getPrompt().toLowerCase(), "jira")));
            boolean gitIntent = isGitRequest(request.getPrompt())
                    || (paginationRequest && (isGitRequest(contextualPrompt) || "git".equals(previousIntent)));
            boolean confluenceIntent = isConfluenceRequest(request.getPrompt())
                    || isKnowledgeRequest(request.getPrompt())
                    || (paginationRequest && (isConfluenceRequest(contextualPrompt) || "confluence".equals(previousIntent)));
            boolean kafkaIntent = isKafkaRequest(request.getPrompt())
                    || (paginationRequest && "kafka".equals(previousIntent));
            boolean serviceNowIntent = isServiceNowRequest(request.getPrompt())
                    || (paginationRequest && "servicenow".equals(previousIntent));
            boolean reportIntent = isReportOrPptRequest(request.getPrompt())
                    || (paginationRequest && "report".equals(previousIntent));
            boolean isOutlookFollowUp = containsAny(request.getPrompt().toLowerCase(Locale.ROOT), "summarize", "summary", "brief", "show them", "list them", "details");
            boolean outlookIntent = isOutlookRequest(request.getPrompt())
                    || ("outlook".equals(previousIntent) && (paginationRequest || isOutlookFollowUp || isOutlookRequest(contextualPrompt)));

            if (paginationRequest && !jiraIntent && !gitIntent && !confluenceIntent && !kafkaIntent && !serviceNowIntent && !reportIntent && !outlookIntent) {
                if ("git".equals(previousIntent)) {
                    gitIntent = true;
                } else if ("confluence".equals(previousIntent)) {
                    confluenceIntent = true;
                } else if ("kafka".equals(previousIntent)) {
                    kafkaIntent = true;
                } else if ("servicenow".equals(previousIntent)) {
                    serviceNowIntent = true;
                } else if ("report".equals(previousIntent)) {
                    reportIntent = true;
                } else if ("outlook".equals(previousIntent)) {
                    outlookIntent = true;
                } else {
                    jiraIntent = true;
                }
            }

            List<Message> history = chatMemoryService.getHistory(sessionId);
            boolean confirmedApproval = isConfirmChange(request.getPrompt(), history);

            String ticketKey = extractIssueKey(request.getPrompt());
            boolean changeIntent = isChangeRequest(request.getPrompt());
            if (ticketKey == null && (changeIntent || confirmedApproval)) {
                ticketKey = extractIssueKey(contextualPrompt);
            }
            // Vulnerability tasks (details, fixes) run with their own tools and skills; Jira/Git/Confluence routing is off.
            boolean vulnTask = vulnerability != null && vulnerability.vulnerabilityTask();
            String vulnMode = !vulnTask ? null : (vulnerability.fix() ? "fix" : "report");
            if (vulnTask) {
                jiraIntent = false;
                gitIntent = false;
                confluenceIntent = false;
                kafkaIntent = false;
                serviceNowIntent = false;
                reportIntent = false;
                outlookIntent = false;
                ticketKey = null;
                changeIntent = false;
            }
            // A Jira key or a change/PR request is a self-contained task driven by the model end-to-end;
            // the deterministic list/search fallbacks below must not overwrite its answer.
            boolean directTicketOrChange = ticketKey != null || changeIntent || confirmedApproval || vulnTask;
            if (changeIntent || confirmedApproval) {
                gitIntent = true;
            }

            boolean isExplicitChat = "chat".equalsIgnoreCase(mode);
            boolean requestedToolIntent = jiraIntent || gitIntent || confluenceIntent || kafkaIntent || serviceNowIntent || reportIntent || outlookIntent || directTicketOrChange;
            boolean isAgentMode = !isExplicitChat && ("agent".equalsIgnoreCase(mode) || requestedToolIntent || paginationRequest);

            if (isAgentMode) {
                if (confirmedApproval) {
                    lastIntentBySession.put(sessionId, "change");
                } else if (jiraIntent || ticketKey != null) {
                    lastIntentBySession.put(sessionId, "jira");
                } else if (gitIntent) {
                    lastIntentBySession.put(sessionId, "git");
                } else if (reportIntent) {
                    lastIntentBySession.put(sessionId, "report");
                } else if (outlookIntent) {
                    lastIntentBySession.put(sessionId, "outlook");
                } else if (kafkaIntent) {
                    lastIntentBySession.put(sessionId, "kafka");
                } else if (serviceNowIntent) {
                    lastIntentBySession.put(sessionId, "servicenow");
                } else if (confluenceIntent) {
                    lastIntentBySession.put(sessionId, "confluence");
                }

                if (!"agent".equals(mode) && requestedToolIntent) {
                    log.info("Session [{}]: Auto-enabling agent tooling for intent-driven request (original mode: {}).", sessionId, mode);
                }
            }

            List<ToolCallback> selectedTools = Collections.emptyList();
            List<String> selectedToolNames = Collections.emptyList();
            Map<String, ToolCallback> toolsByName = new HashMap<>();

            if (isAgentMode) {
                List<ToolCallback> availableTools = currentMcpTools();
                if (availableTools.isEmpty()) {
                    String message = "Agent mode has no MCP tools available. Verify MCP_ENABLED=true and MCP server startup.";
                    chatMemoryService.addAssistantMessage(sessionId, message);
                    return PromptResponse.builder()
                            .response(message)
                            .sessionId(sessionId)
                            .provider(provider.getProviderName())
                            .model(provider.getModel())
                            .toolsUsed(Collections.emptyList())
                            .toolResults(Collections.emptyList())
                            .timestamp(Instant.now())
                            .build();
                }

                selectedTools = confirmedApproval
                        ? resolveApprovedChangeTools()
                        : (vulnTask
                                ? resolveVulnerabilityTools(confirmedApproval)
                                : resolveAgentToolsByIntent(request.getPrompt(), request.getEnabledTools(), confirmedApproval));
                if (selectedTools.isEmpty() && requestedToolIntent) {
                    log.warn("Session [{}]: No routed tools from intent/filters. Falling back to all MCP tools.", sessionId);
                    selectedTools = availableTools;
                }

                selectedToolNames = selectedTools.stream()
                        .map(tool -> tool.getToolDefinition().name())
                        .toList();
                
                for (ToolCallback tool : selectedTools) {
                    toolsByName.put(tool.getToolDefinition().name(), tool);
                }
            }

            List<String> toolsUsed = new ArrayList<>();
            List<Map<String, Object>> toolResults = new ArrayList<>();

            String skillContext = (isAgentMode && !selectedTools.isEmpty())
                    ? (confirmedApproval ? "" : buildSkillContext(jiraIntent, gitIntent, changeIntent, ticketKey != null, vulnMode, kafkaIntent, serviceNowIntent, reportIntent, outlookIntent))
                    : "";
            String prefetchedIssue = (isAgentMode && !selectedTools.isEmpty() && ticketKey != null)
                    ? (!confirmedApproval ? prefetchJiraIssue(ticketKey, toolsByName, toolsUsed, toolResults) : "")
                    : "";

            String copilotPrompt = (isAgentMode && !selectedTools.isEmpty())
                    ? buildAgentPromptWithToolDirective(sessionId, buildCopilotPromptWithSystem(contextualPrompt), request.getPrompt(),
                            selectedToolNames, skillContext, prefetchedIssue, vulnMode, confirmedApproval)
                    : buildChatPrompt(contextualPrompt);

            String responseText = provider.sendAndWait(sessionId, copilotPrompt, selectedTools);
            
            // Agent mode: tool results accumulate in toolsUsed / toolResults (declared above)
            
            if (!toolsByName.isEmpty()) {
                // Look for tool invocation patterns in response
                List<ToolInvocation> detectedTools = detectToolInvocations(responseText, toolsByName.keySet());
                
                int agentRound = 0;
                int maxAgentRounds = !isAgentMode ? 1
                        : "fix".equals(vulnMode) ? MAX_FIX_ROUNDS
                        : "report".equals(vulnMode) ? MAX_VULN_REPORT_ROUNDS : MAX_AGENT_ROUNDS;
                boolean agentTimedOut = false;
                List<ToolInvocation> pendingTools = detectedTools;
                List<String[]> agentSteps = new ArrayList<>();

                while (!pendingTools.isEmpty() && agentRound < maxAgentRounds) {
                    agentRound++;
                    log.info("Session [{}]: Round {} - detected {} tool invocations in response", sessionId, agentRound, pendingTools.size());
                    
                    StringBuilder executionResults = new StringBuilder();
                    boolean batchFailed = false;
                    int executedInRound = 0;
                    for (ToolInvocation invocation : pendingTools) {
                        String callText = abbreviate(invocation.toolName + "(" + invocation.parameters + ")", 700);
                        if (batchFailed || executedInRound >= MAX_TOOL_CALLS_PER_ROUND) {
                            String skipped = batchFailed
                                    ? "SKIPPED: an earlier call in this step failed. Fix that first, then repeat this call."
                                    : "SKIPPED: at most " + MAX_TOOL_CALLS_PER_ROUND + " tool calls run per step. Repeat this call in the next step if it is still needed.";
                            agentSteps.add(new String[]{callText, skipped});
                            executionResults.append("\n[Tool Skipped: ").append(invocation.toolName).append("]\n").append(skipped);
                            continue;
                        }
                        executedInRound++;
                        try {
                            trackJiraSearch(sessionId, invocation);
                            String result = executeToolInvocation(invocation, toolsByName);
                            boolean failedResult = isFailedToolResult(result);
                            if (!failedResult) {
                                updateSessionRemediationState(sessionId, invocation, result);
                            }
                            toolsUsed.add(invocation.toolName);
                            agentSteps.add(new String[]{callText, result});
                            toolResults.add(Map.of(
                                    "tool", invocation.toolName,
                                    "status", failedResult ? "error" : "success",
                                    "result", result
                            ));
                            if (failedResult) {
                                batchFailed = true;
                            }
                            executionResults.append("\n[Tool Result: ").append(invocation.toolName).append("]\n").append(result);
                            log.info("Session [{}]: Tool {} executed successfully", sessionId, invocation.toolName);
                            log.info("Session [{}]: Tool {} full result (length={}): {}", sessionId, invocation.toolName, 
                                result != null ? result.length() : 0, result != null ? result : "(null)");
                        } catch (Exception e) {
                            log.warn("Session [{}]: Failed to execute tool {}: {}", sessionId, invocation.toolName, e.getMessage());
                            toolResults.add(Map.of(
                                    "tool", invocation.toolName,
                                    "status", "error",
                                    "error", String.valueOf(e.getMessage())
                            ));
                            executionResults.append("\n[Tool Error: ").append(invocation.toolName).append("]\n")
                                    .append(e.getMessage());
                            agentSteps.add(new String[]{callText, "ERROR: " + e.getMessage()});
                            batchFailed = true;
                        }
                    }
                    
                    // If tools were executed, append results and get final answer
                    if (!toolResults.isEmpty()) {
                        String refiningPrompt = isAgentMode
                                ? buildAgentContinuationPrompt(sessionId, contextualPrompt, skillContext, selectedToolNames, agentSteps, agentRound, maxAgentRounds)
                                : buildRefiningPrompt(request.getPrompt(), executionResults.toString());
                        try {
                            responseText = provider.sendAndWait(sessionId, refiningPrompt,
                                    isAgentMode ? selectedTools : Collections.<ToolCallback>emptyList());
                        } catch (Exception refineEx) {
                            if (refineEx.getMessage() != null && refineEx.getMessage().contains("timed out")) {
                                responseText = "Tool execution completed successfully.\n\nResults:\n" + executionResults.toString() +
                                        "\n\nNote: Response refinement timed out; showing raw tool results.";
                                log.warn("Session [{}]: Refining prompt timed out after tool execution; returning raw results", sessionId);
                                agentTimedOut = true;
                            } else {
                                throw refineEx;
                            }
                        }
                    }

                    pendingTools = agentTimedOut
                            ? Collections.<ToolInvocation>emptyList()
                            : detectToolInvocations(responseText, toolsByName.keySet());
                }

                if (isAgentMode && !pendingTools.isEmpty()) {
                    responseText = responseText + "\n\n_Stopped after " + maxAgentRounds
                            + " tool steps. Ask me to continue if the task is not finished._";
                }

                if (jiraIntent && !directTicketOrChange) {
                    String lowerResponse = responseText == null ? "" : responseText.toLowerCase();
                    
                    // Check if tool succeeded but returned an error message about invalid component/field
                    String toolResultText = toolResults.isEmpty() ? "" : 
                        toolResults.stream()
                            .map(r -> r.get("result") != null ? r.get("result").toString() : "")
                            .collect(Collectors.joining(" "));
                    String lowerToolResult = toolResultText.toLowerCase();
                    log.info("Session [{}]: Jira request detected. Tool results count: {}. First 500 chars: {}", sessionId, toolResults.size(), 
                        toolResultText.length() > 500 ? toolResultText.substring(0, 500) : toolResultText);
                    
                    boolean looksLikeNoAccess = containsAny(lowerResponse,
                            "don't have access",
                            "do not have access",
                            "no integration",
                            "not connected",
                            "can't access jira",
                            "cannot access jira");
                    
                    boolean looksLikeFieldError = containsAny(lowerToolResult,
                            "isn't a valid component",
                            "is not a valid component",
                            "not a valid component",
                            "unknown field",
                            "invalid field");
                    
                    boolean noSuccessResults = toolResults.stream().noneMatch(result -> "success".equals(result.get("status")));
                    log.info("Session [{}]: Jira checks - looksLikeNoAccess: {}, looksLikeFieldError: {}, noSuccessResults: {}", 
                        sessionId, looksLikeNoAccess, looksLikeFieldError, noSuccessResults);
                    
                    if (looksLikeNoAccess || looksLikeFieldError || (noSuccessResults && detectedTools.isEmpty())) {
                        Optional<Map.Entry<String, ToolCallback>> jiraToolEntry = toolsByName.entrySet().stream()
                                .filter(entry -> entry.getKey().toLowerCase().contains("jira")
                                        && entry.getKey().toLowerCase().contains("search"))
                                .findFirst();

                        if (jiraToolEntry.isPresent()) {
                            String jql = buildJiraJqlFromPrompt(request.getPrompt());
                            String fallbackToolName = jiraToolEntry.get().getKey();
                            List<String> jqlCandidates = buildJiraJqlCandidates(jql);
                            int fallbackStart = Math.max(0, JiraPaging.requestedPage(request.getPrompt()) - 1) * JIRA_PAGE_SIZE;
                            List<String> fallbackParamsCandidates = jqlCandidates.stream()
                                    .map(candidate -> {
                                        String json = "{\"jql\":\"" + escapeJson(JiraPaging.withStableOrder(candidate)) + "\""
                                                + ",\"startAt\":" + fallbackStart + ",\"maxResults\":" + JIRA_PAGE_SIZE + "}";
                                        log.debug("JQL candidate '{}' → JSON: {}", candidate, json);
                                        return json;
                                    })
                                    .toList();
                            log.info("Session [{}]: Jira fallback - {} JQL candidates converted to {} parameter payloads", 
                                sessionId, jqlCandidates.size(), fallbackParamsCandidates.size());

                            try {
                                String jiraResult = tryToolPayloads(jiraToolEntry.get().getValue(), fallbackParamsCandidates);
                                jiraLastSearch.put(sessionId, new JiraPaging.State(JiraPaging.withStableOrder(jql), fallbackStart, JIRA_PAGE_SIZE));
                                toolsUsed.add(fallbackToolName);
                                toolResults.add(Map.of(
                                        "tool", fallbackToolName,
                                        "status", "success",
                                        "result", jiraResult != null ? jiraResult : "(no result)",
                                        "fallback", true
                                ));

                                String executionResults = "\n[Tool Result: " + fallbackToolName + "]\n" + (jiraResult != null ? jiraResult : "(no result)");
                                log.info("Session [{}]: Executed deterministic Jira fallback via tool {}. Result length: {} chars", sessionId, fallbackToolName, 
                                    jiraResult != null ? jiraResult.length() : 0);
                                log.info("Session [{}]: Jira fallback result (first 1000 chars): {}", sessionId, 
                                    jiraResult != null ? jiraResult.substring(0, Math.min(1000, jiraResult.length())) : "(no result)");
                                String refiningPrompt = buildRefiningPrompt(request.getPrompt(), executionResults);
                                try {
                                    responseText = provider.sendAndWait(sessionId, refiningPrompt, Collections.emptyList());
                                } catch (Exception refineEx) {
                                    if (refineEx.getMessage() != null && refineEx.getMessage().contains("timed out")) {
                                        responseText = "Jira search completed with results:\n\n" + (jiraResult != null ? jiraResult : "(no result)") + 
                                                "\n\nNote: Response refinement timed out; showing raw tool results.";
                                        log.warn("Session [{}]: Refining prompt timed out after Jira fallback; returning raw results", sessionId);
                                    } else {
                                        throw refineEx;
                                    }
                                }
                            } catch (Exception fallbackEx) {
                                toolResults.add(Map.of(
                                        "tool", fallbackToolName,
                                        "status", "error",
                                        "error", fallbackEx.getMessage(),
                                        "fallback", true
                                ));
                                responseText = "Jira tool execution failed: " + fallbackEx.getMessage() +
                                        ". Verify Jira MCP connectivity and credentials.";
                                log.warn("Session [{}]: Jira fallback execution failed for tool {}: {}", sessionId, fallbackToolName, fallbackEx.getMessage());
                            }
                        }
                    }
                }

                    if (gitIntent && !directTicketOrChange) {
                        boolean noSuccessfulGitTool = toolResults.stream()
                                .filter(result -> result.get("tool") != null && result.get("tool").toString().toLowerCase().contains("git"))
                                .noneMatch(result -> "success".equals(result.get("status")));
                        String lowerResponse = responseText == null ? "" : responseText.toLowerCase();
                        boolean looksLikeNoAccess = containsAny(lowerResponse,
                            "don't have access",
                            "do not have access",
                            "no integration",
                            "not connected",
                            "github copilot cli",
                            "gh",
                            "not a git repository",
                            "no remote to query",
                            "can't fetch real pr data",
                            "cannot fetch real pr data");

                        if (looksLikeNoAccess || detectedTools.isEmpty() || noSuccessfulGitTool) {
                        Optional<Map.Entry<String, ToolCallback>> listPrToolEntry = toolsByName.entrySet().stream()
                            .filter(entry -> entry.getKey().toLowerCase().contains("git")
                                && entry.getKey().toLowerCase().contains("list_pull_requests"))
                            .findFirst();
                        Optional<Map.Entry<String, ToolCallback>> searchIssuesToolEntry = toolsByName.entrySet().stream()
                            .filter(entry -> entry.getKey().toLowerCase().contains("git")
                                && entry.getKey().toLowerCase().contains("search_issues"))
                            .findFirst();

                        Optional<String[]> ownerRepo = extractOwnerRepoFromPrompt(request.getPrompt());
                        Optional<String> repoOnly = extractRepoNameFromPrompt(request.getPrompt());

                        if (listPrToolEntry.isPresent() && (ownerRepo.isPresent() || repoOnly.isPresent())) {
                            String owner = ownerRepo.map(value -> value[0]).orElseGet(() -> isBlank(defaultGitOrg) ? "ifw-microservices" : defaultGitOrg);
                            String repo = ownerRepo.map(value -> value[1]).orElseGet(repoOnly::get);
                            String fallbackToolName = listPrToolEntry.get().getKey();
                            List<String> payloadCandidates = new ArrayList<>();
                            if (!isBlank(owner)) {
                                payloadCandidates.add("{\"owner\":\"" + escapeJson(owner) + "\",\"repo\":\"" + escapeJson(repo) + "\",\"state\":\"open\"}");
                                payloadCandidates.add("{\"repo\":\"" + escapeJson(owner + "/" + repo) + "\",\"state\":\"open\"}");
                                payloadCandidates.add("{\"repository\":\"" + escapeJson(owner + "/" + repo) + "\",\"state\":\"open\"}");
                            }
                            payloadCandidates.add("{\"owner\":\"ifw-microservices\",\"repo\":\"" + escapeJson(repo) + "\",\"state\":\"open\"}");
                            payloadCandidates.add("{\"repo\":\"" + escapeJson(repo) + "\",\"state\":\"open\"}");
                            payloadCandidates.add("{\"repository\":\"" + escapeJson(repo) + "\",\"state\":\"open\"}");
                            payloadCandidates.add("{\"name\":\"" + escapeJson(repo) + "\",\"state\":\"open\"}");

                            try {
                            String gitResult = tryToolPayloads(listPrToolEntry.get().getValue(), payloadCandidates);
                            toolsUsed.add(fallbackToolName);
                            toolResults.add(Map.of(
                                "tool", fallbackToolName,
                                "status", "success",
                                "result", gitResult != null ? gitResult : "(no result)",
                                "fallback", true
                            ));

                            String executionResults = "\n[Tool Result: " + fallbackToolName + "]\n" + (gitResult != null ? gitResult : "(no result)");
                            String refiningPrompt = buildRefiningPrompt(request.getPrompt(), executionResults);
                            try {
                                responseText = provider.sendAndWait(sessionId, refiningPrompt, Collections.emptyList());
                                log.info("Session [{}]: Executed deterministic Git PR fallback via tool {}", sessionId, fallbackToolName);
                            } catch (Exception refineEx) {
                                if (refineEx.getMessage() != null && refineEx.getMessage().contains("timed out")) {
                                    responseText = "Git PR search completed with results:\n\n" + (gitResult != null ? gitResult : "(no result)") + 
                                            "\n\nNote: Response refinement timed out; showing raw tool results.";
                                    log.warn("Session [{}]: Refining prompt timed out after Git PR fallback; returning raw results", sessionId);
                                } else {
                                    throw refineEx;
                                }
                            }
                            } catch (Exception fallbackEx) {
                            toolResults.add(Map.of(
                                "tool", fallbackToolName,
                                "status", "error",
                                "error", fallbackEx.getMessage(),
                                "fallback", true
                            ));
                            responseText = "Git PR tool execution failed: " + fallbackEx.getMessage() +
                                ". Verify Git MCP connectivity and repository access.";
                            log.warn("Session [{}]: Git PR fallback execution failed for tool {}: {}", sessionId, fallbackToolName, fallbackEx.getMessage());
                            }
                        } else if (searchIssuesToolEntry.isPresent()) {
                            String fallbackToolName = searchIssuesToolEntry.get().getKey();
                            String searchQuery = buildOpenPrSearchQuery(request.getPrompt());
                            List<String> payloadCandidates = List.of(
                                "{\"query\":\"" + escapeJson(searchQuery) + "\"}",
                                "{\"q\":\"" + escapeJson(searchQuery) + "\"}"
                            );

                            try {
                            String gitResult = tryToolPayloads(searchIssuesToolEntry.get().getValue(), payloadCandidates);
                            toolsUsed.add(fallbackToolName);
                            toolResults.add(Map.of(
                                "tool", fallbackToolName,
                                "status", "success",
                                "result", gitResult != null ? gitResult : "(no result)",
                                "fallback", true
                            ));

                            String executionResults = "\n[Tool Result: " + fallbackToolName + "]\n" + (gitResult != null ? gitResult : "(no result)");
                            String refiningPrompt = buildRefiningPrompt(request.getPrompt(), executionResults);
                            try {
                                responseText = provider.sendAndWait(sessionId, refiningPrompt, Collections.emptyList());
                                log.info("Session [{}]: Executed deterministic Git search fallback via tool {}", sessionId, fallbackToolName);
                            } catch (Exception refineEx) {
                                if (refineEx.getMessage() != null && refineEx.getMessage().contains("timed out")) {
                                    responseText = "Git PR search completed with results:\n\n" + (gitResult != null ? gitResult : "(no result)") + 
                                            "\n\nNote: Response refinement timed out; showing raw tool results.";
                                    log.warn("Session [{}]: Refining prompt timed out after Git search fallback; returning raw results", sessionId);
                                } else {
                                    throw refineEx;
                                }
                            }
                            } catch (Exception fallbackEx) {
                            toolResults.add(Map.of(
                                "tool", fallbackToolName,
                                "status", "error",
                                "error", fallbackEx.getMessage(),
                                "fallback", true
                            ));
                            responseText = "Git PR search fallback failed: " + fallbackEx.getMessage() +
                                ". If possible, include repository as owner/repo in your prompt.";
                            log.warn("Session [{}]: Git search fallback failed for tool {}: {}", sessionId, fallbackToolName, fallbackEx.getMessage());
                            }
                        }
                        }
                    }

                    // Deterministic Outlook email intelligence fallback check
                    if (outlookIntent) {
                        boolean noSuccessfulOutlookTool = toolResults.stream()
                                .filter(result -> result.get("tool") != null && result.get("tool").toString().toLowerCase().contains("outlook"))
                                .noneMatch(result -> "success".equals(result.get("status")));
                        String lowerResponse = responseText == null ? "" : responseText.toLowerCase();
                        boolean looksLikeNoAccess = containsAny(lowerResponse,
                                "don't have access", "do not have access", "no integration", "not connected",
                                "cannot access", "can't access", "don't have direct access", "unable to check emails");

                        if (looksLikeNoAccess || detectedTools.isEmpty() || noSuccessfulOutlookTool) {
                            String pLower = request.getPrompt().toLowerCase(Locale.ROOT);
                            boolean wantsSummary = containsAny(pLower, "summarize", "summary", "brief");
                            String folder = extractFolderFromPrompt(request.getPrompt());
                            int count = extractCountFromPrompt(request.getPrompt(), 5);
                            int days = extractDaysFromPrompt(request.getPrompt(), 0);

                            Optional<Map.Entry<String, ToolCallback>> outlookToolEntry = Optional.empty();
                            if (wantsSummary) {
                                outlookToolEntry = toolsByName.entrySet().stream()
                                        .filter(entry -> entry.getKey().equalsIgnoreCase("outlook_summarizeEmails")
                                                || entry.getKey().toLowerCase().contains("summarizeemails"))
                                        .findFirst();
                            } else {
                                outlookToolEntry = toolsByName.entrySet().stream()
                                        .filter(entry -> entry.getKey().equalsIgnoreCase("outlook_fetchEmails")
                                                || entry.getKey().toLowerCase().contains("fetchemails"))
                                        .findFirst();
                            }
                            if (outlookToolEntry.isEmpty()) {
                                outlookToolEntry = toolsByName.entrySet().stream()
                                        .filter(entry -> entry.getKey().equalsIgnoreCase("outlook_summarizeMajorIssues")
                                                || entry.getKey().toLowerCase().contains("summarizemajorissues"))
                                        .findFirst();
                            }
                            if (outlookToolEntry.isEmpty()) {
                                outlookToolEntry = toolsByName.entrySet().stream()
                                        .filter(entry -> entry.getKey().toLowerCase().contains("outlook"))
                                        .findFirst();
                            }

                            if (outlookToolEntry.isPresent()) {
                                String fallbackToolName = outlookToolEntry.get().getKey();
                                List<String> payloadCandidates = new ArrayList<>();
                                if (days > 0) {
                                    payloadCandidates.add(String.format("{\"folder\":\"%s\",\"limit\":%d,\"days\":%d}", folder, count, days));
                                    payloadCandidates.add(String.format("{\"days\":%d}", days));
                                }
                                payloadCandidates.add(String.format("{\"folder\":\"%s\",\"limit\":%d}", folder, count));
                                payloadCandidates.add(String.format("{\"limit\":%d}", count));
                                payloadCandidates.add(String.format("{\"folder\":\"%s\"}", folder));
                                payloadCandidates.add("{}");
                                try {
                                    String outlookResult = tryToolPayloads(outlookToolEntry.get().getValue(), payloadCandidates);
                                    toolsUsed.add(fallbackToolName);
                                    toolResults.add(Map.of(
                                            "tool", fallbackToolName,
                                            "status", "success",
                                            "result", outlookResult != null ? outlookResult : "(no result)",
                                            "fallback", true
                                    ));
                                    String executionResults = "\n[Tool Result: " + fallbackToolName + "]\n" + (outlookResult != null ? outlookResult : "(no result)");
                                    String refiningPrompt = buildRefiningPrompt(request.getPrompt(), executionResults);
                                    try {
                                        responseText = provider.sendAndWait(sessionId, refiningPrompt, Collections.emptyList());
                                    } catch (Exception refineEx) {
                                        if (refineEx.getMessage() != null && refineEx.getMessage().contains("timed out")) {
                                            responseText = "Outlook email scan completed with results:\n\n" + (outlookResult != null ? outlookResult : "(no result)") +
                                                    "\n\nNote: Response refinement timed out; showing raw tool results.";
                                        } else {
                                            throw refineEx;
                                        }
                                    }
                                } catch (Exception fallbackEx) {
                                    toolResults.add(Map.of(
                                            "tool", fallbackToolName,
                                            "status", "error",
                                            "error", fallbackEx.getMessage(),
                                            "fallback", true
                                    ));
                                    responseText = "Outlook email scanning fallback failed: " + fallbackEx.getMessage() +
                                            ". Verify Outlook integration connectivity.";
                                }
                            }
                        }
                    }

                    // Deterministic Executive Presentation / PPT fallback check
                    if (reportIntent) {
                        boolean noSuccessfulReportTool = toolResults.stream()
                                .filter(result -> result.get("tool") != null && result.get("tool").toString().toLowerCase().contains("report"))
                                .noneMatch(result -> "success".equals(result.get("status")));
                        String lowerResponse = responseText == null ? "" : responseText.toLowerCase();
                        boolean looksLikeNoAccess = containsAny(lowerResponse,
                                "don't have access", "do not have access", "cannot generate ppt", "can't generate ppt", "cannot create ppt", "can't create ppt");

                        if (looksLikeNoAccess || detectedTools.isEmpty() || noSuccessfulReportTool) {
                            Optional<Map.Entry<String, ToolCallback>> reportToolEntry = toolsByName.entrySet().stream()
                                    .filter(entry -> entry.getKey().equalsIgnoreCase("report_generateExecutivePpt")
                                            || entry.getKey().toLowerCase().contains("executiveppt")
                                            || entry.getKey().toLowerCase().contains("report_"))
                                    .findFirst();

                            if (reportToolEntry.isPresent()) {
                                int days = extractDaysFromPrompt(request.getPrompt(), 7);
                                String fallbackToolName = reportToolEntry.get().getKey();
                                List<String> payloadCandidates = List.of(
                                        "{\"days\":" + days + ",\"title\":\"EMH Operations & Executive Performance Report\"}",
                                        "{\"days\":" + days + "}",
                                        "{}"
                                );
                                try {
                                    String reportResult = tryToolPayloads(reportToolEntry.get().getValue(), payloadCandidates);
                                    toolsUsed.add(fallbackToolName);
                                    toolResults.add(Map.of(
                                            "tool", fallbackToolName,
                                            "status", "success",
                                            "result", reportResult != null ? reportResult : "(no result)",
                                            "fallback", true
                                    ));
                                    String executionResults = "\n[Tool Result: " + fallbackToolName + "]\n" + (reportResult != null ? reportResult : "(no result)");
                                    String refiningPrompt = buildRefiningPrompt(request.getPrompt(), executionResults);
                                    try {
                                        responseText = provider.sendAndWait(sessionId, refiningPrompt, Collections.emptyList());
                                    } catch (Exception refineEx) {
                                        if (refineEx.getMessage() != null && refineEx.getMessage().contains("timed out")) {
                                            responseText = "Executive report generated with results:\n\n" + (reportResult != null ? reportResult : "(no result)") +
                                                    "\n\nNote: Response refinement timed out; showing raw tool results.";
                                        } else {
                                            throw refineEx;
                                        }
                                    }
                                } catch (Exception fallbackEx) {
                                    toolResults.add(Map.of(
                                            "tool", fallbackToolName,
                                            "status", "error",
                                            "error", fallbackEx.getMessage(),
                                            "fallback", true
                                    ));
                                    responseText = "Executive PPT generation failed: " + fallbackEx.getMessage();
                                }
                            }
                        }
                    }
                }

            chatMemoryService.addAssistantMessage(sessionId, responseText);

            return PromptResponse.builder()
                    .response(responseText)
                    .sessionId(sessionId)
                    .provider(provider.getProviderName())
                    .model(provider.getModel())
                    .toolsUsed(toolsUsed)
                    .toolResults(toolResults)
                    .timestamp(Instant.now())
                    .build();

        } catch (Exception e) {
            log.error("Error processing prompt for session [{}] with provider [{}]: {}", sessionId, provider.getProviderName(), e.getMessage(), e);
            return PromptResponse.builder()
                    .response("Provider error (" + provider.getProviderName() + "): " + e.getMessage())
                    .sessionId(sessionId)
                    .provider(provider.getProviderName())
                    .model(provider != null ? provider.getModel() : "unknown")
                    .timestamp(Instant.now())
                    .build();
        }
    }
    
    private static class ToolInvocation {
        String toolName;
        String parameters;
        
        ToolInvocation(String toolName, String parameters) {
            this.toolName = toolName;
            this.parameters = parameters;
        }
    }

    public static class SessionRemediationState {
        private String projectName;
        private String releaseVersion;
        private String parentGroupId;
        private String parentArtifactId;
        private String parentCurrentVersion;
        private String parentRecommendedVersion;
        private Boolean isSpringBootParent;
        private String springBootBomVersion;
        private String parentPrUrl;
        private String childPrUrl;
        private String branchName;
        private String baseBranch = "develop";
        private boolean testsPassed;
        private boolean cleanInstallPassed;
        private String testSummary;
        private String lastDiff;
        private final List<String> modifiedFiles = new ArrayList<>();
        private boolean waitingForConsent;
        private boolean approvedAndCompleted;
        private final Map<String, String> resolvedSafeVersions = new ConcurrentHashMap<>();

        public String getProjectName() { return projectName; }
        public void setProjectName(String projectName) { this.projectName = projectName; }
        public String getReleaseVersion() { return releaseVersion; }
        public void setReleaseVersion(String releaseVersion) { this.releaseVersion = releaseVersion; }
        public String getParentGroupId() { return parentGroupId; }
        public void setParentGroupId(String parentGroupId) { this.parentGroupId = parentGroupId; }
        public String getParentArtifactId() { return parentArtifactId; }
        public void setParentArtifactId(String parentArtifactId) { this.parentArtifactId = parentArtifactId; }
        public String getParentCurrentVersion() { return parentCurrentVersion; }
        public void setParentCurrentVersion(String parentCurrentVersion) { this.parentCurrentVersion = parentCurrentVersion; }
        public String getParentRecommendedVersion() { return parentRecommendedVersion; }
        public void setParentRecommendedVersion(String parentRecommendedVersion) { this.parentRecommendedVersion = parentRecommendedVersion; }
        public Boolean getIsSpringBootParent() { return isSpringBootParent; }
        public void setIsSpringBootParent(Boolean isSpringBootParent) { this.isSpringBootParent = isSpringBootParent; }
        public String getSpringBootBomVersion() { return springBootBomVersion; }
        public void setSpringBootBomVersion(String springBootBomVersion) { this.springBootBomVersion = springBootBomVersion; }
        public String getParentPrUrl() { return parentPrUrl; }
        public void setParentPrUrl(String parentPrUrl) { this.parentPrUrl = parentPrUrl; }
        public String getChildPrUrl() { return childPrUrl; }
        public void setChildPrUrl(String childPrUrl) { this.childPrUrl = childPrUrl; }
        public String getBranchName() { return branchName; }
        public void setBranchName(String branchName) { this.branchName = branchName; }
        public String getBaseBranch() { return baseBranch; }
        public void setBaseBranch(String baseBranch) { this.baseBranch = baseBranch; }
        public boolean isTestsPassed() { return testsPassed; }
        public void setTestsPassed(boolean testsPassed) { this.testsPassed = testsPassed; }
        public boolean isCleanInstallPassed() { return cleanInstallPassed; }
        public void setCleanInstallPassed(boolean cleanInstallPassed) { this.cleanInstallPassed = cleanInstallPassed; }
        public String getTestSummary() { return testSummary; }
        public void setTestSummary(String testSummary) { this.testSummary = testSummary; }
        public String getLastDiff() { return lastDiff; }
        public void setLastDiff(String lastDiff) { this.lastDiff = lastDiff; }
        public List<String> getModifiedFiles() { return modifiedFiles; }
        public boolean isWaitingForConsent() { return waitingForConsent; }
        public void setWaitingForConsent(boolean waitingForConsent) { this.waitingForConsent = waitingForConsent; }
        public boolean isApprovedAndCompleted() { return approvedAndCompleted; }
        public void setApprovedAndCompleted(boolean approvedAndCompleted) { this.approvedAndCompleted = approvedAndCompleted; }
        public Map<String, String> getResolvedSafeVersions() { return resolvedSafeVersions; }

        public boolean hasMemory() {
            return projectName != null || parentGroupId != null || !resolvedSafeVersions.isEmpty() || parentPrUrl != null || childPrUrl != null || branchName != null;
        }

        public String formatSummary() {
            StringBuilder sb = new StringBuilder();
            if (projectName != null) {
                sb.append("- Current Project: ").append(projectName);
                if (releaseVersion != null) {
                    sb.append(" (Release: ").append(releaseVersion).append(")");
                }
                sb.append("\n");
            }
            if (branchName != null) {
                sb.append("- Active Remediation Branch: ").append(branchName)
                  .append(" (created from ").append(baseBranch != null ? baseBranch : "develop").append(")\n");
            }
            if (parentGroupId != null && parentArtifactId != null) {
                sb.append("- Parent POM: ").append(parentGroupId).append(":").append(parentArtifactId)
                  .append(" (Current Version: ").append(parentCurrentVersion != null ? parentCurrentVersion : "unknown")
                  .append(", isSpringBootParent: ").append(Boolean.TRUE.equals(isSpringBootParent)).append(")\n");
            }
            if (parentRecommendedVersion != null) {
                sb.append("- Parent Recommended Safe Version: ").append(parentRecommendedVersion).append("\n");
            }
            if (parentPrUrl != null) {
                sb.append("- Parent Pull Request: ").append(parentPrUrl).append("\n");
            }
            if (!resolvedSafeVersions.isEmpty()) {
                sb.append("- Resolved Safe Versions: ").append(resolvedSafeVersions).append("\n");
            }
            if (cleanInstallPassed) {
                sb.append("- Verified Test Execution: mvn clean install BUILD SUCCESS");
                if (testSummary != null && !testSummary.isBlank()) {
                    sb.append(" (").append(testSummary).append(")");
                }
                sb.append("\n");
            }
            if (!modifiedFiles.isEmpty()) {
                sb.append("- Modified Files in Feature Branch: ").append(String.join(", ", modifiedFiles)).append("\n");
            }
            if (childPrUrl != null) {
                sb.append("- Child Project Pull Request: ").append(childPrUrl).append("\n");
            }
            if (waitingForConsent) {
                sb.append("- Status: All safe versions applied, test cases passed, and diff ready. Awaiting user consent.\n");
            }
            return sb.toString().trim();
        }
    }

    private void updateSessionRemediationState(String sessionId, ToolInvocation invocation, String result) {
        if (sessionId == null || invocation == null || result == null) {
            return;
        }
        try {
            SessionRemediationState state = sessionRemediationStates.computeIfAbsent(sessionId, k -> new SessionRemediationState());
            String tool = invocation.toolName.toLowerCase(Locale.ROOT);
            JsonNode json = null;
            try {
                String clean = result.stripLeading();
                if (clean.startsWith("{") || clean.startsWith("[")) {
                    json = objectMapper.readTree(clean);
                }
            } catch (Exception ignored) {}

            if (tool.contains("clonerepo")) {
                if (json != null && json.has("repo")) {
                    String repo = json.path("repo").asText(null);
                    if (state.getProjectName() == null || (!repo.toLowerCase(Locale.ROOT).contains("parent") && !repo.equalsIgnoreCase("ifw-microservices"))) {
                        state.setProjectName(repo);
                    }
                    if (json.has("parentGroupId")) state.setParentGroupId(json.path("parentGroupId").asText(null));
                    if (json.has("parentArtifactId")) state.setParentArtifactId(json.path("parentArtifactId").asText(null));
                    if (json.has("parentVersion")) state.setParentCurrentVersion(json.path("parentVersion").asText(null));
                    if (json.has("isSpringBootParent")) state.setIsSpringBootParent(json.path("isSpringBootParent").asBoolean(false));
                    if (json.has("springBootBomVersion")) state.setSpringBootBomVersion(json.path("springBootBomVersion").asText(null));
                }
            } else if (tool.contains("createbranch")) {
                if (json != null && json.has("branch")) {
                    state.setBranchName(json.path("branch").asText(null));
                    if (json.has("fromBranch")) {
                        state.setBaseBranch(json.path("fromBranch").asText("develop"));
                    }
                }
            } else if (tool.contains("runmaven")) {
                if (json != null) {
                    boolean success = json.path("success").asBoolean(false);
                    String ts = json.path("testSummary").asText(null);
                    if (ts != null && !ts.isBlank()) {
                        state.setTestSummary(ts);
                    }
                    if (success) {
                        state.setTestsPassed(true);
                        state.setCleanInstallPassed(true);
                    }
                }
            } else if (tool.contains("replaceinfile") || tool.contains("writefile")) {
                if (json != null && json.has("path")) {
                    String filePath = json.path("path").asText("");
                    if (!filePath.isBlank() && !state.getModifiedFiles().contains(filePath)) {
                        state.getModifiedFiles().add(filePath);
                    }
                }
            } else if (tool.contains("gitdiff")) {
                if (json != null && json.has("diff")) {
                    String diff = json.path("diff").asText("");
                    if (!diff.isBlank()) {
                        state.setLastDiff(diff);
                        state.setWaitingForConsent(true);
                    }
                }
            } else if (tool.contains("findsafeversion")) {
                if (json != null && json.has("dependency")) {
                    String dep = json.path("dependency").asText("");
                    String rec = json.path("recommended").asText(null);
                    String latest = json.path("latestStable").asText(null);
                    String safe = (rec != null && !rec.isBlank()) ? rec : latest;
                    if (!dep.isBlank() && safe != null && !safe.isBlank()) {
                        state.getResolvedSafeVersions().put(dep, safe);
                        if (dep.contains("spring-boot-starter-parent") || dep.contains("spring-boot-dependencies")) {
                            state.setParentRecommendedVersion(safe);
                        }
                    }
                }
            } else if (tool.contains("createpullrequest")) {
                if (json != null && json.has("url")) {
                    String prUrl = json.path("url").asText("");
                    String head = json.path("head").asText("");
                    String repo = json.path("repo").asText("");
                    state.setApprovedAndCompleted(true);
                    state.setWaitingForConsent(false);
                    if (head.contains("parent") || repo.contains("parent") || repo.equalsIgnoreCase("ifw-microservices")) {
                        state.setParentPrUrl(prUrl);
                    } else {
                        state.setChildPrUrl(prUrl);
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("Session [{}]: Error updating session remediation state: {}", sessionId, ex.getMessage());
        }
    }
    
    /**
     * Detect tool invocations in model response.
     * Looks for patterns like:
     * - TOOL_CALL: tool_name(param1: value1, param2: value2)
     * - [TOOL] tool_name: arguments
     * - {{tool_name|argument}}
     */
    private List<ToolInvocation> detectToolInvocations(String response, Set<String> availableTools) {
        List<ToolInvocation> invocations = new ArrayList<>();
        
        if (response == null || availableTools.isEmpty()) {
            return invocations;
        }

        // Pattern 1: TOOL_CALL: tool_name(...). Supports nested parentheses and arguments that span several
        // lines (for example pretty-printed JSON for push_files / create_pull_request).
        String[] lines = response.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.startsWith("TOOL_CALL:")) {
                continue;
            }
            if (parenDepth(trimmed) <= 0) {
                ToolInvocation single = parseToolCallLine(trimmed, availableTools);
                if (single != null) {
                    invocations.add(single);
                }
                continue;
            }
            StringBuilder block = new StringBuilder(trimmed);
            int end = i;
            boolean closed = false;
            while (end + 1 < lines.length && end - i < MAX_TOOL_CALL_LINES) {
                String next = lines[end + 1];
                if (next.trim().startsWith("TOOL_CALL:")) {
                    break;
                }
                end++;
                block.append('\n').append(next);
                if (parenDepth(block.toString()) <= 0) {
                    closed = true;
                    break;
                }
            }
            ToolInvocation parsed = parseToolCallLine(closed ? block.toString().trim() : trimmed, availableTools);
            if (parsed != null) {
                invocations.add(parsed);
            }
            if (closed) {
                i = end;
            }
        }
        
        // Pattern 2: [TOOL] tool_name: arguments
        java.util.regex.Pattern pattern2 = java.util.regex.Pattern.compile("\\[TOOL\\]\\s*([a-zA-Z0-9_]+)\\s*:\\s*([^\\n]+)");
        java.util.regex.Matcher matcher2 = pattern2.matcher(response);
        while (matcher2.find()) {
            String toolName = matcher2.group(1);
            String params = matcher2.group(2);
            if (availableTools.contains(toolName)) {
                invocations.add(new ToolInvocation(toolName, params));
            }
        }
        
        return invocations;
    }

    private ToolInvocation parseToolCallLine(String line, Set<String> availableTools) {
        if (line == null) {
            return null;
        }

        String trimmed = line.trim();
        if (!trimmed.startsWith("TOOL_CALL:")) {
            return null;
        }

        String payload = trimmed.substring("TOOL_CALL:".length()).trim();
        int openParen = payload.indexOf('(');
        int closeParen = payload.lastIndexOf(')');
        if (openParen <= 0 || closeParen <= openParen) {
            return null;
        }

        String toolName = payload.substring(0, openParen).trim();
        if (!availableTools.contains(toolName)) {
            return null;
        }

        String params = payload.substring(openParen + 1, closeParen).trim();
        return new ToolInvocation(toolName, params);
    }
    
    private String executeToolInvocation(ToolInvocation invocation, Map<String, ToolCallback> toolsByName) throws Exception {
        ToolCallback tool = toolsByName.get(invocation.toolName);
        if (tool == null) {
            throw new IllegalArgumentException("Tool not found: " + invocation.toolName);
        }
        
        String normalizedParameters = normalizeToolParameters(invocation.parameters);
        if (isJiraSearchTool(invocation.toolName)) {
            normalizedParameters = JiraPaging.sanitizeSearchArguments(objectMapper, normalizedParameters);
        }
        guardToolCall(invocation.toolName, normalizedParameters);
        String result = tool.call(normalizedParameters);
        return result != null ? result : "(no result)";
    }

    private String normalizeToolParameters(String rawParameters) {
        if (rawParameters == null || rawParameters.isBlank()) {
            return "{}";
        }

        String value = rawParameters.trim();
        if (value.startsWith("{") || value.startsWith("[")) {
            return repairJsonControlChars(value);
        }

        Map<String, String> parsedParameters = parseNamedParameters(value);
        if (!parsedParameters.isEmpty()) {
            String json = parsedParameters.entrySet().stream()
                    .map(entry -> "\"" + escapeJson(entry.getKey()) + "\":" + toJsonValue(entry.getKey(), entry.getValue()))
                    .collect(Collectors.joining(","));
            return "{" + json + "}";
        }

        int separatorIndex = value.indexOf(':');
        if (separatorIndex > 0) {
            String key = value.substring(0, separatorIndex).trim();
            String parameterValue = value.substring(separatorIndex + 1).trim();

            if (key.matches("[a-zA-Z0-9_]+")) {
                String unquoted = stripWrappingQuotes(parameterValue);
                return "{\"" + escapeJson(key) + "\":" + toJsonValue(key, unquoted) + "}";
            }
        }

        return value;
    }

    private Map<String, String> parseNamedParameters(String value) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (value == null || value.isBlank()) {
            return parsed;
        }

        List<String> parts = splitTopLevel(value, ',');
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }

            int separatorIndex = part.indexOf(':');
            if (separatorIndex <= 0) {
                return Collections.emptyMap();
            }

            String key = part.substring(0, separatorIndex).trim();
            String parameterValue = part.substring(separatorIndex + 1).trim();
            if (!key.matches("[a-zA-Z0-9_]+")) {
                return Collections.emptyMap();
            }

            parsed.put(key, stripWrappingQuotes(parameterValue));
        }

        return parsed;
    }

    private List<String> splitTopLevel(String value, char delimiter) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        int parenDepth = 0;

        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (!inSingleQuote && !inDoubleQuote) {
                if (ch == '(') {
                    parenDepth++;
                } else if (ch == ')' && parenDepth > 0) {
                    parenDepth--;
                }
            }

            if (ch == delimiter && !inSingleQuote && !inDoubleQuote && parenDepth == 0) {
                parts.add(current.toString().trim());
                current.setLength(0);
                continue;
            }

            current.append(ch);
        }

        if (!current.isEmpty()) {
            parts.add(current.toString().trim());
        }
        return parts;
    }

    private List<String> buildJiraJqlCandidates(String primaryJql) {
        String statusClause = " AND status NOT IN (\"Closed\", \"In Production\", \"Cancelled\")";
        
        // If we have a primary JQL, use it FIRST, with fallbacks as alternatives
        if (primaryJql != null && !primaryJql.isBlank()) {
            List<String> candidates = new ArrayList<>();
            
            // PRIMARY: Try the generated JQL first (which has Components and labels)
            candidates.add(primaryJql);
            
            // FALLBACK 1: Try project key
            candidates.add("project = EMH" + statusClause);
            candidates.add("project = \"EMH\"" + statusClause);
            
            // FALLBACK 2: Try alternative syntax variants
            candidates.add("(Components in (\"EMH\", \"EMH-Middleware\") OR labels in (\"EMH\", \"EMH-Middleware\"))" + statusClause);
            
            // FALLBACK 3: Try individual Components field
            candidates.add("Components in (\"EMH\", \"EMH-Middleware\")" + statusClause);
            candidates.add("labels in (\"EMH\", \"EMH-Middleware\")" + statusClause);
            
            log.info("Jira JQL fallback candidates generated (primaryJql provided): {} total variants", candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                log.info("  Candidate {}: {}", i + 1, candidates.get(i));
            }
            return candidates.stream().distinct().toList();
        }
        
        // Fallback when no primary JQL provided
        List<String> defaults = List.of(
                "project = EMH" + statusClause,
                "project = \"EMH\"" + statusClause,
                "(Components in (\"EMH\", \"EMH-Middleware\") OR labels in (\"EMH\", \"EMH-Middleware\"))" + statusClause,
                "Components in (\"EMH\", \"EMH-Middleware\")" + statusClause,
                "labels in (\"EMH\", \"EMH-Middleware\")" + statusClause
        );
        log.info("Jira JQL fallback candidates generated (no primaryJql): {} total variants", defaults.size());
        for (int i = 0; i < defaults.size(); i++) {
            log.info("  Candidate {}: {}", i + 1, defaults.get(i));
        }
        return defaults;
    }

    private String tryToolPayloads(ToolCallback tool, List<String> payloadCandidates) throws Exception {
        Exception lastError = null;
        log.info("Trying {} payload candidates for tool execution", payloadCandidates.size());
        for (int idx = 0; idx < payloadCandidates.size(); idx++) {
            String payload = payloadCandidates.get(idx);
            log.info("================================");
            log.info("Attempting candidate {} of {}", idx + 1, payloadCandidates.size());
            log.info("Payload JSON (length {}): {}", payload.length(), payload);
            log.info("================================");
            try {
                String result = tool.call(payload);
                log.info("✓ Candidate {} succeeded! Result length: {} chars", idx + 1, result != null ? result.length() : 0);
                if (result != null && result.length() > 0) {
                    log.info("Result preview (first 800 chars): {}", result.substring(0, Math.min(800, result.length())));
                }
                
                // Check if Jira returned an error in the response
                if (result != null && result.toLowerCase().contains("\"success\":false")) {
                    log.warn("✗ Candidate {} returned Jira error (success=false), trying next candidate...", idx + 1);
                    lastError = new Exception("Jira returned error: " + result.substring(0, Math.min(200, result.length())));
                    continue; // Try next candidate
                }
                
                return result;
            } catch (Exception ex) {
                log.warn("✗ Candidate {} failed with error: {}", idx + 1, ex.getMessage());
                lastError = ex;
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("No payload candidates were provided for tool execution.");
    }

    private Optional<String[]> extractOwnerRepoFromPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return Optional.empty();
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)");
        java.util.regex.Matcher matcher = pattern.matcher(prompt);
        if (!matcher.find()) {
            return Optional.empty();
        }

        return Optional.of(new String[]{matcher.group(1), matcher.group(2)});
    }

    private Optional<String> extractRepoNameFromPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return Optional.empty();
        }

        String value = prompt.toLowerCase();
        List<java.util.regex.Pattern> patterns = List.of(
                java.util.regex.Pattern.compile("(?:repo|repository)\\s*(?:name\\s*)?(?:is\\s*)?[:=]?\\s*([a-z0-9_.-]+)"),
                java.util.regex.Pattern.compile("([a-z0-9_.-]+)\\s+(?:repo|repository)"),
                java.util.regex.Pattern.compile("(?:for|of)\\s+([a-z0-9_.-]+)\\s+open\\s+(?:prs|pr|pull requests)")
        );

        for (java.util.regex.Pattern pattern : patterns) {
            java.util.regex.Matcher matcher = pattern.matcher(value);
            if (matcher.find()) {
                String candidate = matcher.group(1);
                if (isLikelyRepoName(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }

        java.util.regex.Matcher hyphenatedMatcher = java.util.regex.Pattern.compile("\\b([a-z0-9]+(?:[-._][a-z0-9]+)+)\\b").matcher(value);
        while (hyphenatedMatcher.find()) {
            String candidate = hyphenatedMatcher.group(1);
            if (isLikelyRepoName(candidate)) {
                return Optional.of(candidate);
            }
        }

        return Optional.empty();
    }

    private boolean isLikelyRepoName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        Set<String> stopWords = Set.of("open", "prs", "pr", "pull", "request", "requests", "list", "show", "page", "more", "next");
        if (stopWords.contains(value)) {
            return false;
        }

        return value.length() >= 3;
    }

    private String buildOpenPrSearchQuery(String prompt) {
        StringBuilder query = new StringBuilder("is:pr is:open");
        Optional<String[]> ownerRepo = extractOwnerRepoFromPrompt(prompt);
        Optional<String> repoOnly = extractRepoNameFromPrompt(prompt);
        if (ownerRepo.isPresent()) {
            query.append(" repo:").append(ownerRepo.get()[0]).append("/").append(ownerRepo.get()[1]);
        } else if (repoOnly.isPresent()) {
            if (!isBlank(defaultGitOrg)) {
                query.append(" repo:").append(defaultGitOrg).append("/").append(repoOnly.get());
            } else {
                query.append(" ").append(repoOnly.get()).append(" in:title");
            }
        }
        query.append(" sort:updated-desc");
        return query.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String stripWrappingQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }

        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }

        return value;
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
    
    // ======================== Agent helpers: intents, skills, guards ========================

    private String extractIssueKey(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        Matcher matcher = ISSUE_KEY_PATTERN.matcher(prompt);
        while (matcher.find()) {
            if (!NON_ISSUE_KEY_PREFIXES.contains(matcher.group(1))) {
                return matcher.group();
            }
        }
        return null;
    }

    private boolean isChangeRequest(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String value = prompt.toLowerCase(Locale.ROOT).trim();
        return CREATE_PR_PATTERN.matcher(value).find()
                || CHANGE_WORK_PATTERN.matcher(value).find()
                || CHANGE_ON_ISSUE_PATTERN.matcher(value).find()
                || CONFIRM_CHANGE_PATTERN.matcher(value).find();
    }

    private boolean isKnowledgeRequest(String prompt) {
        if (prompt == null) {
            return false;
        }
        String value = prompt.trim().toLowerCase(Locale.ROOT);
        if (value.length() < 8 || isGreeting(value)) {
            return false;
        }
        boolean hasConfluenceOrDocs = containsAny(value, "confluence", "wiki", "documentation", "doc ", "docs ", "page", "release process");
        boolean hasDomainKeywords = containsAny(value, "emh", "connector", "middleware", "kafka", "architecture");
        return hasConfluenceOrDocs || (hasDomainKeywords && (containsAny(value, "what is", "how to", "explain", "describe", "find", "search", "details") || value.endsWith("?")));
    }

    private void loadSkills() {
        try {
            Resource[] resources = applicationContext.getResources("classpath*:prompts/skills/*.txt");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                String name = filename.endsWith(".txt") ? filename.substring(0, filename.length() - 4) : filename;
                skills.put(name, new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            log.info("Agent skills loaded: {}", skills.keySet());
        } catch (IOException ex) {
            log.warn("Unable to load agent skills: {}", ex.getMessage());
        }
    }

    private String buildSkillContext(boolean jiraIntent, boolean gitIntent, boolean changeIntent, boolean hasTicketKey,
                                     String vulnMode) {
        return buildSkillContext(jiraIntent, gitIntent, changeIntent, hasTicketKey, vulnMode, false);
    }

    private String buildSkillContext(boolean jiraIntent, boolean gitIntent, boolean changeIntent, boolean hasTicketKey,
                                     String vulnMode, boolean kafkaIntent) {
        return buildSkillContext(jiraIntent, gitIntent, changeIntent, hasTicketKey, vulnMode, kafkaIntent, false);
    }

    private String buildSkillContext(boolean jiraIntent, boolean gitIntent, boolean changeIntent, boolean hasTicketKey,
                                     String vulnMode, boolean kafkaIntent, boolean serviceNowIntent) {
        return buildSkillContext(jiraIntent, gitIntent, changeIntent, hasTicketKey, vulnMode, kafkaIntent, serviceNowIntent, false, false);
    }

    private String buildSkillContext(boolean jiraIntent, boolean gitIntent, boolean changeIntent, boolean hasTicketKey,
                                     String vulnMode, boolean kafkaIntent, boolean serviceNowIntent,
                                     boolean reportIntent, boolean outlookIntent) {
        if (skills.isEmpty()) {
            return "";
        }
        Set<String> names = new LinkedHashSet<>();
        if (vulnMode != null) {
            names.add("fix".equals(vulnMode) ? "vulnerability-fix" : "vulnerability-report");
        } else {
            if (jiraIntent || gitIntent || changeIntent || hasTicketKey) {
                names.add("jira-repo-context");
            }
            if (changeIntent) {
                names.add("jira-change-to-pr");
            }
            if (kafkaIntent) {
                names.add("kafka-connect-operations");
            }
            if (serviceNowIntent) {
                names.add("servicenow-operations");
            }
            if (reportIntent) {
                names.add("ppt-report-generation");
            }
            names.add("confluence-research");
        }

        StringBuilder builder = new StringBuilder("\n\n[Skills - follow these playbooks]\n");
        for (String name : names) {
            String text = skills.get(name);
            if (text != null) {
                builder.append("\n--- Skill: ").append(name).append(" ---\n").append(text.strip()).append("\n");
            }
        }
        return builder.toString();
    }

    private String prefetchJiraIssue(String issueKey, Map<String, ToolCallback> toolsByName,
                                     List<String> toolsUsed, List<Map<String, Object>> toolResults) {
        Optional<Map.Entry<String, ToolCallback>> entry = toolsByName.entrySet().stream()
                .filter(e -> e.getKey().endsWith("jira_getIssue"))
                .findFirst();
        if (entry.isEmpty()) {
            return "";
        }
        String toolName = entry.get().getKey();
        try {
            String result = entry.get().getValue().call("{\"issueKey\":\"" + escapeJson(issueKey) + "\"}");
            String text = result == null ? "" : result;
            if (text.replace(" ", "").toLowerCase(Locale.ROOT).contains("\"success\":false")) {
                toolResults.add(Map.of("tool", toolName, "status", "error", "error", abbreviate(text, 500), "prefetch", true));
                log.warn("Jira pre-fetch for {} returned an error result", issueKey);
                return "";
            }
            toolsUsed.add(toolName);
            toolResults.add(Map.of("tool", toolName, "status", "success", "result", text, "prefetch", true));
            log.info("Jira pre-fetch for {} succeeded ({} chars)", issueKey, text.length());
            return abbreviate(text, MAX_PREFETCH_CHARS);
        } catch (Exception ex) {
            toolResults.add(Map.of("tool", toolName, "status", "error", "error", String.valueOf(ex.getMessage()), "prefetch", true));
            log.warn("Jira pre-fetch for {} failed: {}", issueKey, ex.getMessage());
            return "";
        }
    }

    private String findToolName(List<String> names, String suffix) {
        for (String name : names) {
            if (name.endsWith(suffix)) {
                return name;
            }
        }
        return suffix;
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "\n...[truncated " + (value.length() - max) + " chars]";
    }

    private String buildAgentContinuationPrompt(String contextualPrompt, String skillContext, List<String> toolNames,
                                                List<String[]> steps, int round, int maxRounds) {
        return buildAgentContinuationPrompt(null, contextualPrompt, skillContext, toolNames, steps, round, maxRounds);
    }

    private String buildAgentContinuationPrompt(String sessionId, String contextualPrompt, String skillContext, List<String> toolNames,
                                                List<String[]> steps, int round, int maxRounds) {
        StringBuilder builder = new StringBuilder();
        builder.append("[System]\n").append(systemPrompt).append(skillContext == null ? "" : skillContext);
        if (sessionId != null) {
            SessionRemediationState remState = sessionRemediationStates.get(sessionId);
            if (remState != null && remState.hasMemory()) {
                builder.append("\n\n[Session Memory: Prior Findings & Resolved Safe Versions]\n")
                       .append(remState.formatSummary()).append("\n");
            }
        }
        builder.append("\n\n[User]\n").append(contextualPrompt);
        builder.append("\n\n[Tool steps completed so far]\n").append(buildTranscript(steps));
        builder.append("\n\n[Available tools]\n");
        for (String name : toolNames) {
            builder.append("- ").append(name).append("\n");
        }
        builder.append("\n[Instructions - step ").append(round).append(" of at most ").append(maxRounds).append("]\n");
        builder.append("1. Study the tool results above. They are the source of truth.\n");
        builder.append("2. If more information or actions are needed to complete the request, output the next TOOL_CALL lines now ")
                .append("(exact tool names, one call per line or one single-line JSON argument object, independent calls together, max ")
                .append(MAX_TOOL_CALLS_PER_ROUND).append(" per step) and nothing else.\n");
        builder.append("3. If a call shows ERROR or SKIPPED, fix the cause (parameters, spelling, prerequisites) and repeat it, ")
                .append("or explain the problem if it cannot be fixed.\n");
        builder.append("4. Do not repeat a call that already succeeded. Do not call tools for information you already have.\n");
        builder.append("5. When the request is fully handled, write the final answer for the user with NO TOOL_CALL line. ")
                .append("Cite Confluence pages and link Jira/GitHub items as described in your instructions.\n");
        return builder.toString();
    }

    private String buildTranscript(List<String[]> steps) {
        int remaining = MAX_TRANSCRIPT_CHARS;
        String[] rendered = new String[steps.size()];
        for (int i = steps.size() - 1; i >= 0; i--) {
            String call = steps.get(i)[0];
            String result = steps.get(i)[1] == null ? "" : steps.get(i)[1];
            int allowed = Math.max(600, Math.min(MAX_SINGLE_RESULT_CHARS, remaining));
            String shown = abbreviate(result, allowed);
            rendered[i] = "Step " + (i + 1) + ": TOOL_CALL: " + call + "\nResult:\n" + shown + "\n";
            remaining -= shown.length();
        }
        return String.join("\n", rendered);
    }

    private void guardToolCall(String toolName, String json) {
        String name = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        for (String blocked : BLOCKED_TOOL_SUFFIXES) {
            if (name.endsWith(blocked)) {
                throw new IllegalStateException("BLOCKED: '" + blocked + "' is not allowed for this agent.");
            }
        }
        boolean writesToBranch = WRITE_TO_BRANCH_TOOL_SUFFIXES.stream().anyMatch(name::endsWith);
        boolean opensPr = name.endsWith("create_pull_request");
        if (!writesToBranch && !opensPr) {
            return;
        }
        JsonNode node = readJson(json);
        if (node == null) {
            return;
        }
        if (writesToBranch) {
            String branch = node.path("branch").asText("");
            if (branch.isBlank() || isProtectedBranch(branch)) {
                throw new IllegalStateException("BLOCKED: commits are only allowed on a feature branch (feature/<JIRA-KEY>). "
                        + "Branch '" + branch + "' is protected or missing. Create the feature branch with create_branch first.");
            }
        }
        if (opensPr) {
            String head = node.path("head").asText("");
            if (head.contains(":")) {
                head = head.substring(head.indexOf(':') + 1);
            }
            if (head.isBlank() || isProtectedBranch(head)) {
                throw new IllegalStateException("BLOCKED: the pull request head must be a feature branch (feature/<JIRA-KEY>), not '"
                        + head + "'.");
            }
        }
    }

    private boolean isProtectedBranch(String branch) {
        String value = branch.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("refs/heads/")) {
            value = value.substring("refs/heads/".length());
        }
        return PROTECTED_BRANCHES.contains(value)
                || value.startsWith("release/") || value.startsWith("release-")
                || value.startsWith("hotfix/");
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Escapes raw newlines/tabs that appear inside JSON string values (models often emit them unescaped). */
    private String repairJsonControlChars(String json) {
        StringBuilder out = new StringBuilder(json.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                    out.append(ch);
                } else if (ch == '\\') {
                    escaped = true;
                    out.append(ch);
                } else if (ch == '"') {
                    inString = false;
                    out.append(ch);
                } else if (ch == '\n') {
                    out.append("\\n");
                } else if (ch == '\r') {
                    out.append("\\r");
                } else if (ch == '\t') {
                    out.append("\\t");
                } else {
                    out.append(ch);
                }
            } else {
                if (ch == '"') {
                    inString = true;
                }
                out.append(ch);
            }
        }
        return out.toString();
    }

    private String toJsonValue(String key, String value) {
        String v = value == null ? "" : value.trim();
        if (NUMERIC_TOOL_PARAMS.contains(key) && v.matches("-?\\d+")) {
            return v;
        }
        if (BOOLEAN_TOOL_PARAMS.contains(key) && (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false"))) {
            return v.toLowerCase(Locale.ROOT);
        }
        return "\"" + escapeJson(value) + "\"";
    }

    /** Parenthesis depth of the text, ignoring parentheses inside double-quoted strings. */
    private int parenDepth(String text) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
        }
        return depth;
    }

    private String buildRefiningPrompt(String originalPrompt, String toolResults) {
        return "[Original question]\n" + originalPrompt + 
               "\n\n[Tool execution results]\n" + toolResults +
               "\n\n[Your task]\nBased on the tool results above, provide a comprehensive answer to the original question.";
    }

    public void clearProviderSession(String sessionId) {
        for (LlmProviderService provider : providers.values()) {
            try {
                provider.deleteSession(sessionId);
            } catch (Exception e) {
                log.debug("Unable to clear session [{}] for provider [{}]: {}", sessionId, provider.getProviderName(), e.getMessage());
            }
        }
    }

    public LlmProviderService resolveProvider(String requestedProvider) {
        if (requestedProvider != null && !requestedProvider.isBlank() && !"default".equalsIgnoreCase(requestedProvider)) {
            String key = requestedProvider.trim().toLowerCase();
            LlmProviderService p = providers.get(key);
            if (p != null && p.isAvailable()) {
                return p;
            }
            if (("githubcopilot".equals(key) || "copilot-sdk".equals(key)) && providers.containsKey("copilot")) {
                LlmProviderService cp = providers.get("copilot");
                if (cp.isAvailable()) return cp;
            }
            if (("agy".equals(key) || "gemini".equals(key) || "antigravity".equals(key) || "antigravity-cli".equals(key))) {
                LlmProviderService ap = providers.get("antigravity");
                if (ap == null) ap = providers.get("antigravity-cli");
                if (ap != null && ap.isAvailable()) return ap;
            }
            if (p != null) {
                log.warn("Requested provider [{}] is registered but not currently available. Attempting fallback.", key);
            }
        }

        // Check configured default
        String defaultKey = (configuredProvider != null && !configuredProvider.isBlank())
                ? configuredProvider.trim().toLowerCase() : "copilot";
        LlmProviderService defaultP = providers.get(defaultKey);
        if (defaultP != null && defaultP.isAvailable()) {
            return defaultP;
        }

        // Automatic fallback: pick any available provider
        for (LlmProviderService p : providers.values()) {
            if (p.isAvailable()) {
                log.info("Default provider [{}] is unavailable; falling back to available provider [{}]",
                        defaultKey, p.getProviderName());
                return p;
            }
        }

        return defaultP;
    }

    public Map<String, Object> getProviderInfo() {
        LlmProviderService active = resolveProvider(null);
        List<Map<String, Object>> providerList = new ArrayList<>();
        for (LlmProviderService p : providers.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", p.getProviderName());
            entry.put("name", p.getDisplayName());
            entry.put("available", p.isAvailable());
            entry.put("model", p.getModel());
            providerList.add(entry);
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("configuredProvider", configuredProvider);
        info.put("activeProvider", active != null ? active.getProviderName() : "none");
        info.put("activeModel", active != null ? active.getModel() : "unknown");
        info.put("providers", providerList);
        return info;
    }

    /**
     * Resolve session ID - use existing or create a new one.
     */
    private String resolveSessionId(String sessionId) {
        if (sessionId != null && !sessionId.isBlank() && chatMemoryService.sessionExists(sessionId)) {
            return sessionId;
        }
        return chatMemoryService.createSession();
    }

    /**
     * Build a contextual prompt that includes relevant conversation history.
     * This allows the AI model to understand the conversation context.
     */
    private String buildContextualPrompt(List<Message> history, String currentPrompt) {
        if (history.size() <= 1) {
            // Only the current message, no context needed
            return currentPrompt;
        }

        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("[Previous conversation context]\n");

        // Include recent history (last 4 messages, capped to prevent prompt bloat)
        List<Message> previousMessages = history.subList(0, history.size() - 1);
        int start = Math.max(0, previousMessages.size() - 4);
        List<Message> recent = previousMessages.subList(start, previousMessages.size());
        for (Message msg : recent) {
            String role = msg.getMessageType().name().toLowerCase();
            String text = abbreviate(msg.getText(), 800);
            contextBuilder.append(role).append(": ").append(text).append("\n");
        }

        contextBuilder.append("\n[Current question]\n");
        contextBuilder.append(currentPrompt);

        return contextBuilder.toString();
    }

    private String buildChatPrompt(String prompt) {
        return "[System]\nYou are EMH AI ASSISTANT, an intelligent enterprise assistant. "
                + "Provide direct, concise, and helpful answers. Respond in the same language as the user's question.\n\n[User]\n" + prompt;
    }

    /**
     * List all available MCP tools.
     */
    public List<Map<String, String>> listAvailableTools() {
        return currentMcpTools().stream()
                .map(tool -> {
                    Map<String, String> info = new HashMap<>();
                    info.put("name", tool.getToolDefinition().name());
                    info.put("description", tool.getToolDefinition().description());
                    return info;
                })
                .collect(Collectors.toList());
    }

    public Map<String, Object> getMcpDiagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("directToolBeanCount", mcpTools.size());

        List<String> providerBeans = new ArrayList<>();
        List<ToolCallback> discovered = new ArrayList<>();

        for (Map.Entry<String, Object> entry : applicationContext.getBeansOfType(Object.class).entrySet()) {
            Object bean = entry.getValue();
            List<ToolCallback> extracted = extractToolCallbacks(bean);
            if (!extracted.isEmpty()) {
                providerBeans.add(entry.getKey() + " -> " + bean.getClass().getName());
                discovered.addAll(extracted);
            }
        }

        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback callback : discovered) {
            byName.putIfAbsent(callback.getToolDefinition().name(), callback);
        }

        diagnostics.put("providerBeans", providerBeans);
        diagnostics.put("discoveredToolCount", byName.size());
        diagnostics.put("toolNames", byName.keySet());
        diagnostics.put("mcpEnabledEnv", System.getenv("MCP_ENABLED"));
        return diagnostics;
    }

    /**
     * Resolve mode - defaults to "chat" if not specified.
     */
    private String resolveMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "chat";
        }
        return mode.toLowerCase();
    }

    private List<ToolCallback> filterTools(String[] enabledTools) {
        List<ToolCallback> availableTools = currentMcpTools();
        if (enabledTools == null || enabledTools.length == 0 ||
                Arrays.asList(enabledTools).contains("all")) {
            return availableTools;
        }

        Set<String> enabledSet = Arrays.stream(enabledTools)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        Set<String> expandedSet = new LinkedHashSet<>(enabledSet);
        if (enabledSet.contains("git")) {
            expandedSet.add("github");
            expandedSet.add("repo");
        }
        if (enabledSet.contains("workspace")) {
            expandedSet.add("workspace_");
        }
        if (enabledSet.contains("maven")) {
            expandedSet.add("maven_");
        }
        if (enabledSet.contains("jira")) {
            expandedSet.add("atlassian");
            expandedSet.add("issue");
            expandedSet.add("ticket");
        }
        if (enabledSet.contains("confluence")) {
            expandedSet.add("wiki");
            expandedSet.add("page");
        }
        if (enabledSet.contains("kafka")) {
            expandedSet.add("kafka_");
            expandedSet.add("connect");
        }
        if (enabledSet.contains("servicenow")) {
            expandedSet.add("servicenow_");
            expandedSet.add("incident");
            expandedSet.add("service_request");
            expandedSet.add("ritm");
        }
        if (enabledSet.contains("outlook") || enabledSet.contains("mail")) {
            expandedSet.add("outlook_");
            expandedSet.add("email");
        }
        if (enabledSet.contains("report") || enabledSet.contains("ppt")) {
            expandedSet.add("report_");
            expandedSet.add("ppt");
        }
        if (enabledSet.contains("jfrog") || enabledSet.contains("xray") || enabledSet.contains("artifactory")) {
            expandedSet.add("jfrog_");
            expandedSet.add("xray_");
            expandedSet.add("artifactory");
            expandedSet.add("vulnerability");
            expandedSet.add("cve");
            expandedSet.add("xray");
            expandedSet.add("docker");
        }

        return availableTools.stream()
                .filter(tool -> {
                    String toolName = tool.getToolDefinition().name().toLowerCase();
                    String description = tool.getToolDefinition().description() == null
                            ? ""
                            : tool.getToolDefinition().description().toLowerCase();
                    return expandedSet.stream().anyMatch(token -> toolName.contains(token) || description.contains(token));
                })
                .collect(Collectors.toList());
    }

    private List<ToolCallback> resolveAgentToolsByIntent(String prompt, String[] enabledTools) {
        return resolveAgentToolsByIntent(prompt, enabledTools, false);
    }

    private List<ToolCallback> resolveAgentToolsByIntent(String prompt, String[] enabledTools, boolean confirmedApproval) {
        List<ToolCallback> availableTools = currentMcpTools();
        if (enabledTools != null && enabledTools.length > 0 && !Arrays.asList(enabledTools).contains("all")) {
            List<ToolCallback> explicitFiltered = filterTools(enabledTools);
            return explicitFiltered.isEmpty() ? availableTools : explicitFiltered;
        }

        if (prompt == null || prompt.isBlank()) {
            return availableTools;
        }

        String value = prompt.toLowerCase(Locale.ROOT);
        Set<String> inferred = new LinkedHashSet<>();

        boolean hasTicketKey = extractIssueKey(prompt) != null;
        boolean changeRequest = isChangeRequest(prompt);
        boolean jiraRequest = containsAny(value, "jira", "ticket", "issue", "backlog", "sprint") || hasTicketKey;
        if (jiraRequest) {
            inferred.add("jira");
        }
        if (jiraRequest || changeRequest || isGitRequest(prompt)) {
            inferred.add("git");
        }
        if (changeRequest) {
            inferred.add("workspace");
            inferred.add("maven");
        }
        if (isConfluenceRequest(prompt) || isKnowledgeRequest(prompt)) {
            inferred.add("confluence");
        }
        if (isKafkaRequest(prompt)) {
            inferred.add("kafka");
        }
        if (isServiceNowRequest(prompt)) {
            inferred.add("servicenow");
        }
        if (isReportOrPptRequest(prompt)) {
            inferred.add("report");
            inferred.add("outlook");
            inferred.add("servicenow");
            inferred.add("jira");
            inferred.add("jfrog");
        }
        if (isOutlookRequest(prompt)) {
            inferred.add("outlook");
        }
        if (isJfrogRequest(prompt) || VulnerabilityChatService.isVulnerabilityRequest(prompt)) {
            inferred.add("jfrog");
        }

        if (inferred.isEmpty()) {
            return Collections.emptyList();
        }

        List<ToolCallback> inferredFiltered = filterTools(inferred.toArray(new String[0]));
        List<ToolCallback> tools = inferredFiltered.isEmpty() ? availableTools : inferredFiltered;
        if (!confirmedApproval && (changeRequest || hasTicketKey)) {
            return tools.stream()
                    .filter(tool -> {
                        String name = tool.getToolDefinition().name();
                        return !name.contains("commitAndPush") && !name.contains("createPullRequest");
                    })
                    .collect(Collectors.toList());
        }
        return tools;
    }

    /** Tools for vulnerability work: JFrog/Xray, Maven version lookup and the sandboxed local workspace. */
    private List<ToolCallback> resolveVulnerabilityTools() {
        return resolveVulnerabilityTools(false);
    }

    private List<ToolCallback> resolveVulnerabilityTools(boolean confirmedApproval) {
        return currentMcpTools().stream()
                .filter(tool -> {
                    String name = tool.getToolDefinition().name();
                    if (!confirmedApproval && (name.contains("commitAndPush") || name.contains("createPullRequest"))) {
                        return false;
                    }
                    return LOCAL_TOOL_PREFIXES.stream().anyMatch(name::startsWith);
                })
                .collect(Collectors.toList());
    }

    /** True when a tool returned a JSON object with success=false (our local tools report errors this way). */
    private boolean isFailedToolResult(String result) {
        if (result == null || result.length() < 12) {
            return false;
        }
        String head = result.stripLeading();
        if (!head.startsWith("{") && !head.startsWith("\"{")) {
            return false;
        }
        if (!head.replace(" ", "").contains("\"success\":false") && !head.replace(" ", "").contains("\\\"success\\\":false")) {
            return false;
        }
        JsonNode node = readJson(head.startsWith("\"") ? unwrapJsonString(head) : head);
        return node != null && node.has("success") && !node.path("success").asBoolean(true);
    }

    private String unwrapJsonString(String quoted) {
        try {
            return objectMapper.readValue(quoted, String.class);
        } catch (Exception ex) {
            return quoted;
        }
    }

    private List<ToolCallback> currentMcpTools() {
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback callback : mcpTools) {
            byName.putIfAbsent(callback.getToolDefinition().name(), callback);
        }
        Map<String, Object> beans = applicationContext.getBeansOfType(Object.class);
        for (Object bean : beans.values()) {
            for (ToolCallback callback : extractToolCallbacks(bean)) {
                byName.putIfAbsent(callback.getToolDefinition().name(), callback);
            }
        }
        return new ArrayList<>(byName.values());
    }

    private List<ToolCallback> extractToolCallbacks(Object bean) {
        try {
            Method method = bean.getClass().getMethod("getToolCallbacks");
            Object result = method.invoke(bean);
            if (result instanceof ToolCallback[] array) {
                return Arrays.asList(array);
            }
            if (result instanceof Collection<?> collection) {
                return collection.stream()
                        .filter(ToolCallback.class::isInstance)
                        .map(ToolCallback.class::cast)
                        .toList();
            }
        } catch (Exception ignored) {
        }
        return Collections.emptyList();
    }

    private static boolean containsAny(String value, String... words) {
        for (String word : words) {
            if (value.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private boolean isConfirmChange(String prompt) {
        return isConfirmChange(prompt, Collections.emptyList());
    }

    private boolean isConfirmChange(String prompt, List<Message> history) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String clean = prompt.toLowerCase(Locale.ROOT).trim();
        boolean hasAcceptPhrase = clean.contains("accept and create")
                || clean.contains("accept & create")
                || clean.contains("accept the proposed")
                || clean.contains("code changes and test cases are approved")
                || clean.contains("proposed code changes are approved")
                || clean.contains("proceed directly with applying");
        if (hasAcceptPhrase) {
            return true;
        }

        boolean hasPrior = hasPriorProposal(history);
        if (hasPrior || clean.equals("accept") || clean.startsWith("accept ") || clean.equals("proceed")
                || clean.equals("yes") || clean.equals("lgtm") || clean.equals("looks good")) {
            return CONFIRM_CHANGE_PATTERN.matcher(clean).find()
                    || clean.startsWith("yes") || clean.startsWith("accept") || clean.startsWith("proceed")
                    || clean.startsWith("approved") || clean.startsWith("looks good") || clean.startsWith("lgtm")
                    || clean.startsWith("go ahead") || clean.startsWith("apply") || clean.contains("create pr")
                    || clean.contains("raise pr") || clean.contains("create pull request") || clean.contains("raise pull request");
        }

        return CONFIRM_CHANGE_PATTERN.matcher(clean).find() && (clean.contains("proposed") || clean.contains("proposal") || clean.contains("diff"));
    }

    private boolean hasPriorProposal(List<Message> history) {
        if (history == null || history.size() <= 1) {
            return false;
        }
        for (int i = history.size() - 2; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg.getMessageType().name().equalsIgnoreCase("assistant")) {
                String text = msg.getText();
                if (text != null) {
                    String lower = text.toLowerCase(Locale.ROOT);
                    if (lower.contains("```diff") || lower.contains("```patch")
                            || lower.contains("proposed file changes")
                            || lower.contains("proposed vulnerability fix")
                            || lower.contains("dependency upgrades")
                            || lower.contains("reply with 'accept'")
                            || lower.contains("reply with \"accept\"")
                            || lower.contains("reply with accept")
                            || lower.contains("user confirmation required")
                            || lower.contains("user confirmation is required")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<ToolCallback> resolveApprovedChangeTools() {
        Set<String> executionToolNames = Set.of(
                "workspace_replaceInFile",
                "workspace_writeFile",
                "workspace_runMaven",
                "workspace_gitStatus",
                "workspace_gitDiff",
                "workspace_commitAndPush",
                "workspace_createPullRequest",
                "workspace_readFile"
        );
        return currentMcpTools().stream()
                .filter(tool -> executionToolNames.contains(tool.getToolDefinition().name()))
                .collect(Collectors.toList());
    }

    private String extractTargetRepo(String originalPrompt, String contextualPrompt) {
        return extractTargetRepo(originalPrompt, contextualPrompt, null);
    }

    private String extractTargetRepo(String originalPrompt, String contextualPrompt, String sessionId) {
        String repo = findRepoInText(originalPrompt);
        if (repo != null) {
            return repo;
        }
        repo = findRepoInText(contextualPrompt);
        if (repo != null) {
            return repo;
        }
        if (sessionId != null) {
            SessionRemediationState state = sessionRemediationStates.get(sessionId);
            if (state != null && state.getProjectName() != null) {
                return state.getProjectName();
            }
        }
        return null;
    }

    private String findRepoInText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m1 = Pattern.compile("ifw-microservices/([A-Za-z0-9_.-]+)").matcher(text);
        while (m1.find()) {
            String candidate = cleanRepoCandidate(m1.group(1));
            if (candidate != null) {
                return candidate;
            }
        }
        Matcher m2 = Pattern.compile("(?:target\\s+)?(?:repo(?:sitory)?|component|service)\\s*[:=]?\\s*[`'\"]?([A-Za-z0-9_.-]+)[`'\"]?", Pattern.CASE_INSENSITIVE).matcher(text);
        while (m2.find()) {
            String candidate = cleanRepoCandidate(m2.group(1));
            if (candidate != null) {
                return candidate;
            }
        }
        Matcher m3 = Pattern.compile("(?:vulnerabilit(?:y|ies)\\s+in|vulnerability\\s+fix\\s+for|Git repository \\(project\\):|remediate the parent project|parent project)\\s*[:=]?\\s*[`'\"]?([A-Za-z0-9_.-]+)[`'\"]?", Pattern.CASE_INSENSITIVE).matcher(text);
        while (m3.find()) {
            String candidate = cleanRepoCandidate(m3.group(1));
            if (candidate != null) {
                return candidate;
            }
        }
        Matcher m4 = Pattern.compile("feature/vulnerability-fix-([A-Za-z0-9_.-]+?)-(?:\\d+\\.\\d+\\.\\d+|fix)", Pattern.CASE_INSENSITIVE).matcher(text);
        while (m4.find()) {
            String candidate = cleanRepoCandidate(m4.group(1));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String cleanRepoCandidate(String raw) {
        if (raw == null) {
            return null;
        }
        String candidate = raw.replaceAll("^[\\s`'\"(\\[]+|[\\s`'\",.:;)\\]]+$", "").trim();
        if (candidate.isBlank() || candidate.equalsIgnoreCase("ifw-microservices") || candidate.equalsIgnoreCase("and") || !isLikelyRepoName(candidate)) {
            return null;
        }
        return candidate;
    }

    private String buildAgentPromptWithToolDirective(String contextualPrompt, String originalPrompt, List<String> selectedToolNames,
                                                     String skillContext, String prefetchedIssue, String vulnMode) {
        return buildAgentPromptWithToolDirective(null, contextualPrompt, originalPrompt, selectedToolNames, skillContext, prefetchedIssue, vulnMode, isConfirmChange(originalPrompt));
    }

    private String buildAgentPromptWithToolDirective(String contextualPrompt, String originalPrompt, List<String> selectedToolNames,
                                                     String skillContext, String prefetchedIssue, String vulnMode, boolean confirmedApproval) {
        return buildAgentPromptWithToolDirective(null, contextualPrompt, originalPrompt, selectedToolNames, skillContext, prefetchedIssue, vulnMode, confirmedApproval);
    }

    private String buildAgentPromptWithToolDirective(String sessionId, String contextualPrompt, String originalPrompt, List<String> selectedToolNames,
                                                     String skillContext, String prefetchedIssue, String vulnMode, boolean confirmedApproval) {
        StringBuilder builder = new StringBuilder();
        builder.append(contextualPrompt);

        if (sessionId != null) {
            SessionRemediationState remState = sessionRemediationStates.get(sessionId);
            if (remState != null && remState.hasMemory()) {
                builder.append("\n\n[Session Memory: Prior Findings & Completed Work in this Session]\n");
                builder.append(remState.formatSummary()).append("\n");
                builder.append("CRITICAL: Re-use all prior findings, POM parent details, and resolved safe versions above. Do NOT repeat safe version lookups or duplicate Git cloning. Zero rework required.\n");
            }
        }

        builder.append("\n\n[CRITICAL: Tool Invocation Format]\n");
        builder.append("When you need to use a tool, output EXACTLY this format (on its own line):\n");
        builder.append("TOOL_CALL: tool_name(parameter1: value1, parameter2: value2)\n\n");
        builder.append("Available tools:\n");
        for (String toolName : selectedToolNames) {
            builder.append("- ").append(toolName).append("\n");
        }
        builder.append("\n[Execution Rules]\n");
        if (vulnMode != null && !confirmedApproval) {
            builder.append("1. Use the tools below for every number, version and file content. Never guess counts or versions.\n");
        } else {
            builder.append("1. For Jira searches: Use 'TOOL_CALL: spring_ai_mcp_client_jira_mcp_server_jira_searchIssues(jql: <JQL_QUERY>)'\n");
        }
        builder.append("2. If external enterprise data is needed to fulfill the request, execute tool calls BEFORE providing your final answer.\n");
        builder.append("3. If the user prompt is a general question, greeting, conversational remark, or explanation that does not require querying external enterprise systems, answer DIRECTLY without making any tool calls.\n");
        builder.append("4. Use tool results as the source of truth for your response when tools are invoked.\n");
        builder.append("5. Copy tool names EXACTLY from the Available tools list. Use only these tools - no shell, git/gh CLI or local files.\n");
        builder.append("6. Lists, objects and text with commas or line breaks: use ONE single-line JSON argument object, e.g. TOOL_CALL: name({\"owner\":\"o\",\"repo\":\"r\"}).\n");
        builder.append("7. You receive the tool results back and may call more tools. Finish with the final answer and no TOOL_CALL line.\n");

        String issueKey = extractIssueKey(originalPrompt);
        if (issueKey == null) {
            issueKey = extractIssueKey(contextualPrompt);
        }
        String targetRepo = extractTargetRepo(originalPrompt, contextualPrompt, sessionId);

        if (confirmedApproval) {
            SessionRemediationState remState = sessionId != null ? sessionRemediationStates.get(sessionId) : null;
            builder.append("\n[Change Task - User Approved: Execution Phase (NO REWORK)]\n");
            builder.append("The user has EXPLICITLY APPROVED and ACCEPTED the proposed code/dependency changes for ");
            if (issueKey != null) {
                builder.append("Jira ").append(issueKey);
            } else if (targetRepo != null) {
                builder.append("project ").append(targetRepo);
            } else {
                builder.append("the change request");
            }
            if (targetRepo != null && issueKey != null) {
                builder.append(" in repository ifw-microservices/").append(targetRepo);
            }
            builder.append(".\n\n");
            builder.append("CRITICAL: ALL CODE/POM ANALYSIS, DEPENDENCY UPGRADES, AND DIFF FORMULATION WERE ALREADY COMPLETED AND APPROVED IN THE PREVIOUS STEP.\n");
            builder.append("The repository is ALREADY cloned and the feature branch is ALREADY checked out in the workspace.\n");
            builder.append("DO NOT call Jira, Confluence, Xray, cloneRepo, createBranch, listFiles, or searchText. Zero rework is required.\n\n");

            if (remState != null && remState.isCleanInstallPassed() && remState.getBranchName() != null) {
                builder.append("FAST-FORWARD ENABLED: mvn clean install has ALREADY PASSED (BUILD SUCCESS) on branch '")
                       .append(remState.getBranchName()).append("' and file changes are already applied.\n")
                       .append("DO NOT re-run Maven tests. DO NOT re-modify files. FAST-FORWARD DIRECTLY to commit and create pull request:\n")
                       .append("1. Commit and push changes: TOOL_CALL: ").append(findToolName(selectedToolNames, "workspace_commitAndPush"))
                       .append("(repo: \"").append(targetRepo != null ? targetRepo : (remState.getProjectName() != null ? remState.getProjectName() : "<repo>"))
                       .append("\", message: \"").append(issueKey != null ? issueKey + ": Fix" : "Fix vulnerabilities in " + (targetRepo != null ? targetRepo : (remState.getProjectName() != null ? remState.getProjectName() : "project"))).append("\")\n")
                       .append("2. Create pull request targeting develop: TOOL_CALL: ").append(findToolName(selectedToolNames, "workspace_createPullRequest"))
                       .append("(repo: \"").append(targetRepo != null ? targetRepo : (remState.getProjectName() != null ? remState.getProjectName() : "<repo>"))
                       .append("\", title: \"").append(issueKey != null ? issueKey + ": " : "").append("Fix vulnerabilities in ").append(targetRepo != null ? targetRepo : (remState.getProjectName() != null ? remState.getProjectName() : "project"))
                       .append("\", head: \"").append(remState.getBranchName()).append("\", baseBranch: \"").append(remState.getBaseBranch() != null ? remState.getBaseBranch() : "develop")
                       .append("\", body: \"## Remediation Summary\\nAll dependencies upgraded and verified with mvn clean install (BUILD SUCCESS).\\n\\n")
                       .append(remState.getTestSummary() != null ? remState.getTestSummary() : "All unit and integration tests passed.").append("\"). Report the PR link to the user.\n");
            } else {
                builder.append("Proceed DIRECTLY and IMMEDIATELY to execute the approved changes in order:\n");
                builder.append("1. If changes are not yet applied, apply the finalized changes directly from the approved unified diff in the previous message using workspace_replaceInFile or workspace_writeFile.\n");
                builder.append("2. Ensure test cases pass: compile and test with TOOL_CALL: ").append(findToolName(selectedToolNames, "workspace_runMaven"))
                       .append("(repo: \"").append(targetRepo != null ? targetRepo : "<repo>").append("\", goals: \"clean test\") (or mvn clean install), and verify startup with spring-boot:run if applicable.\n");
                builder.append("3. Commit and push changes: TOOL_CALL: ").append(findToolName(selectedToolNames, "workspace_commitAndPush"))
                       .append("(repo: \"").append(targetRepo != null ? targetRepo : "<repo>").append("\", message: \"").append(issueKey != null ? issueKey + ": Fix" : "Fix vulnerabilities in " + (targetRepo != null ? targetRepo : "project")).append("\"), ");
                builder.append("and create the pull request targeting develop: TOOL_CALL: ").append(findToolName(selectedToolNames, "workspace_createPullRequest"))
                       .append("(repo: \"").append(targetRepo != null ? targetRepo : "<repo>").append("\", title: \"").append(issueKey != null ? issueKey + ": " : "").append("Fix vulnerabilities in ").append(targetRepo != null ? targetRepo : "project").append("\", head: \"feature/")
                       .append(issueKey != null ? issueKey : (targetRepo != null ? "vulnerability-fix-" + targetRepo : "fix"))
                       .append("\", baseBranch: \"develop\", body: \"...\"). Report the PR link to the user.\n");
                builder.append("4. IF test failure, compilation error, or runtime issue occurs:\n");
                builder.append("   - Fix the issue in the code/tests on the feature branch.\n");
                builder.append("   - If additional code change is required beyond the user's initial approval, STOP and TAKE CONSENT AGAIN from the user before proceeding.\n");
                builder.append("   - Re-verify until successful.\n");
            }
            builder.append(skillContext == null ? "" : skillContext);
            return builder.toString();
        }

        if (vulnMode != null) {
            builder.append("\n[Vulnerability Task]\n");
            if ("fix".equals(vulnMode)) {
                builder.append("[Vulnerability Fix Task - Workflow: Parent-First Check -> Branch from develop -> Feature Branch Analysis -> Apply Safe Upgrades -> Run all test cases with mvn clean install -> Fix Failures -> Show Diff -> Take Consent]\n");
                builder.append("The user wants to fix vulnerabilities in project ").append(targetRepo != null ? targetRepo : "<repo>")
                        .append(". Execute skill vulnerability-fix step by step:\n\n");
                builder.append("STEP 0: PARENT PROJECT INSPECTION & PARENT-FIRST PROTOCOL\n");
                builder.append("When cloning the repository with workspace_cloneRepo(repo: \"").append(targetRepo != null ? targetRepo : "<repo>").append("\", branch: \"develop\"), inspect the root pom.xml <parent> section:\n");
                builder.append("a) IF parent is spring-boot-starter-parent (isSpringBootParent == true or parentArtifactId contains \"spring-boot\"):\n");
                builder.append("   - Check latest stable compatible Spring Boot version using maven_findSafeVersion(groupId: \"org.springframework.boot\", artifactId: \"spring-boot-starter-parent\", currentVersion: parentVersion).\n");
                builder.append("   - Upgrading the Spring Boot parent version is the most effective fix: it automatically bumps all managed dependencies (Tomcat embed, Netty, Jackson, Spring Security, SnakeYAML, Logback) to mutually compatible, vulnerability-free versions.\n");
                builder.append("   - Include the Spring Boot parent version bump in the proposed diff.\n");
                builder.append("b) IF parent is an internal organizational repository (e.g. ifw-microservices, ifw-parent, emh-parent, or another shared parent POM):\n");
                builder.append("   - Inform the user in chat:\n");
                builder.append("     \"⚠️ **Parent Project Remediation Required First**: This microservice inherits shared dependencies, BOMs, and plugin management from parent project `<parentGroupId>:<parentArtifactId>` (`<parentVersion>`). Many libraries are getting inherited from there. We need to fix parent project vulnerabilities first because many libraries are getting inherited from there. Would you like me to create a feature branch from develop for `<parentArtifactId>`, remediate its vulnerabilities, run all test cases with mvn clean install and fix failures, show diff, and raise a pull request targeting develop first?\"\n");
                builder.append("   - STOP and get user consent before proceeding with the parent project remediation.\n");
                builder.append("   - Upon user consent: clone parent repo, create feature branch from develop, fix parent vulnerabilities, compile and run all test cases (workspace_runMaven clean install), verify startup if applicable, show diff, take consent, and raise a PR targeting develop.\n");
                builder.append("c) Child Project Step (after parent PR):\n");
                builder.append("   - Update the <parent><version> in the child project's root pom.xml to the newly remediated parent version.\n");
                builder.append("   - Remediate remaining child-level specific vulnerabilities.\n\n");
                builder.append("STEP 1: CREATE FEATURE BRANCH FROM DEVELOP\n");
                builder.append("Create feature branch feature/vulnerability-fix-").append(targetRepo != null ? targetRepo : "<repo>")
                       .append("-<release> from develop: clone with workspace_cloneRepo(repo: \"").append(targetRepo != null ? targetRepo : "<repo>")
                       .append("\", branch: \"develop\") and create branch with workspace_createBranch(repo: \"").append(targetRepo != null ? targetRepo : "<repo>")
                       .append("\", branch: \"feature/vulnerability-fix-").append(targetRepo != null ? targetRepo : "<repo>").append("-<release>\", fromBranch: \"develop\").\n");
                builder.append("   (NEVER refer to master as a base branch; master is not in sync with develop. If develop is missing, ask the user).\n\n");
                builder.append("STEP 2: DO CODE AND POM ANALYSIS IN THE FEATURE BRANCH ONLY\n");
                builder.append("Analyze, inspect, and read files ONLY in this feature branch. Identify where each vulnerable dependency is defined (properties, dependencyManagement, direct dependency, or transitive).\n\n");
                builder.append("STEP 3: RESOLVE SAFE VERSIONS\n");
                builder.append("Query maven_findSafeVersion for each vulnerable dependency. Enforce library family cohesion (upgrade all artifacts in a family like jackson-*, spring-*, netty-*, tomcat-*, logback-* to the same matching version).\n\n");
                builder.append("STEP 4: APPLY CODE & POM CHANGES IN THE FEATURE BRANCH\n");
                builder.append("Apply safe version upgrades and dependency fixes using workspace_replaceInFile or workspace_writeFile on the feature branch.\n\n");
                builder.append("STEP 5: RUN ALL TEST CASES WITH MVN CLEAN INSTALL & FIX TEST FAILURES (MANDATORY BEFORE SHOWING DIFF)\n");
                builder.append("Execute: TOOL_CALL: ").append(findToolName(selectedToolNames, "workspace_runMaven"))
                       .append("(repo: \"").append(targetRepo != null ? targetRepo : "<repo>").append("\", goals: \"clean install\").\n");
                builder.append("Run all the test cases and verify the build. IF compilation error or test failure occurs:\n");
                builder.append("   - Fix the issues in the code or tests on the feature branch using workspace_replaceInFile or workspace_writeFile.\n");
                builder.append("   - Re-run TOOL_CALL: ").append(findToolName(selectedToolNames, "workspace_runMaven"))
                       .append("(repo: \"").append(targetRepo != null ? targetRepo : "<repo>").append("\", goals: \"clean install\") until all test cases pass cleanly (BUILD SUCCESS).\n\n");
                builder.append("STEP 6: SHOW FILE CHANGES DIFFERENCE AND TAKE CONSENT (MANDATORY GUARDRAIL)\n");
                builder.append("Check the diff using TOOL_CALL: ").append(findToolName(selectedToolNames, "workspace_gitDiff"))
                       .append("(repo: \"").append(targetRepo != null ? targetRepo : "<repo>").append("\").\n");
                builder.append("Present the results clearly to the user:\n");
                builder.append("1. Dependency Upgrades Table (dependency | installed version | safe version | CVEs resolved).\n");
                builder.append("2. Test Execution Summary (confirm that mvn clean install executed all test cases and all passed cleanly: Tests run, Failures: 0, Errors: 0, Skipped: 0).\n");
                builder.append("3. File-by-file unified diff (```diff ``` blocks) showing the exact code and POM changes.\n");
                builder.append("STOP and ask the user:\n");
                builder.append("\"Please review the proposed file changes difference and test execution results above. Reply with 'Accept' / 'Proceed' to raise the pull request targeting develop, or tell me what to modify.\"\n");
                builder.append("CRITICAL GUARDRAIL: Do NOT commit, push, or create the pull request before user consent. The PR creation tool is withheld until the user explicitly accepts.\n");
            } else {
                builder.append("Answer the vulnerability question with the xray_/jfrog_/maven_ tools, following skill vulnerability-report. "
                        + "Default scope is the latest release of every module; honour the project, module and release given in the request. "
                        + "Offer the download link of the report when it helps.\n");
            }
            builder.append(skillContext == null ? "" : skillContext);
            return builder.toString();
        }

        boolean changeRequest = isChangeRequest(originalPrompt);
        boolean jiraRequest = isJiraRequest(originalPrompt);

        if (issueKey != null) {
            builder.append("\n[Jira Task]\nThe request refers to Jira issue ").append(issueKey).append(".\n");
            if (prefetchedIssue != null && !prefetchedIssue.isBlank()) {
                builder.append("Its details were already fetched - do NOT call getIssue again.\n");
                builder.append("[Jira issue ").append(issueKey).append(" details]\n").append(prefetchedIssue).append("\n");
            } else {
                builder.append("Fetch it first: TOOL_CALL: ").append(findToolName(selectedToolNames, "jira_getIssue"))
                        .append("(issueKey: ").append(issueKey).append(")\n");
            }
        } else if (jiraRequest && !changeRequest) {
            String jql = buildJiraJqlFromPrompt(originalPrompt);
            builder.append("\n[Jira Task]\n");
            builder.append("Execute this Jira search with the tool:\n");
            builder.append("TOOL_CALL: spring_ai_mcp_client_jira_mcp_server_jira_searchIssues(jql: ").append(jql).append(")\n");
            builder.append("Page size is 25. The search result carries \"total\" (all matches): state \"showing 1-25 of <total>\" and tell the user "
                    + "they can say \"next 25\" for the next page (the platform pages with startAt, never with an OFFSET clause in the JQL).\n");
            builder.append("Then provide ticket details from results.");
        }

        if (jiraRequest || issueKey != null || changeRequest) {
            builder.append("\n[Git Task]\nAfter the Jira data, look up the related repository, branches and pull requests in the GitHub Enterprise organisation "
                    + "ifw-microservices with the Git tools (skill jira-repo-context) and report the Jira and Git information together.");
        } else if (isGitRequest(originalPrompt)) {
            builder.append("\n[Git Task]\nUse Git MCP tools to fetch repository/issue information before responding.");
        }

        if (changeRequest) {
            builder.append("\n[Change Task - Workflow: Create Branch from develop -> Analyze in Feature Branch Only -> Show Diff -> Take Consent]\n");
            builder.append("The user wants a code change and a pull request for a Jira ticket. Execute skill jira-change-to-pr step by step:\n");
            builder.append("1. FIRST create the feature branch feature/").append(issueKey != null ? issueKey : "<KEY>")
                   .append(" from develop: clone the repository with workspace_cloneRepo(repo: \"").append(targetRepo != null ? targetRepo : "<repo>")
                   .append("\", branch: \"develop\") and create the feature branch with workspace_createBranch(repo: \"").append(targetRepo != null ? targetRepo : "<repo>")
                   .append("\", branch: \"feature/").append(issueKey != null ? issueKey : "<KEY>").append("\", fromBranch: \"develop\").\n");
            builder.append("   (If develop branch is NOT present in the repository, STOP and ask the user which branch should be used to create the feature branch).\n");
            builder.append("2. DO CODE ANALYSIS IN THE FEATURE BRANCH ONLY: Analyze, inspect, and read files ONLY in this feature branch. NEVER refer to master as a base branch (master is not in sync with develop). Locate files using workspace_listFiles, workspace_readFile, or workspace_searchText on the feature branch.\n");
            builder.append("3. SHOW CODE DIFFERENCE: Present the exact proposed file changes in unified diff format (```diff ``` blocks) file-by-file.\n");
            builder.append("4. TAKE CONSENT: STOP and ask the user for confirmation: \"Please review the proposed file changes above. Reply with 'Accept' / 'Proceed' to make the code changes, compile, test, verify with spring-boot:run, and raise the pull request, or tell me what to modify.\"\n");
            builder.append("   DO NOT make file edits, commit, or create PR before user confirmation!\n");
        }

        if (!confirmedApproval) {
            builder.append("\n[Confluence Task]\nSearch Confluence (skill confluence-research) when the request asks for information or an explanation, "
                    + "when you describe an existing project or integration, and before making any change for a Jira. "
                    + "For plain Jira ticket lists or pull request lists Confluence is not needed. "
                    + "Cite page titles and links, and say clearly when nothing relevant was found.");
        }

        if (isOutlookRequest(originalPrompt) || (sessionId != null && "outlook".equals(lastIntentBySession.get(sessionId)))) {
            String folder = extractFolderFromPrompt(originalPrompt);
            int count = extractCountFromPrompt(originalPrompt, 5);
            int days = extractDaysFromPrompt(originalPrompt, 0);
            boolean isSummary = containsAny(originalPrompt.toLowerCase(Locale.ROOT), "summarize", "summary", "brief");

            builder.append("\n[Outlook Email Intelligence Task]\n");
            builder.append("The user is asking about emails in Outlook.\n");
            builder.append("CRITICAL OUTLOOK RULES:\n");
            builder.append("1. STRICT INBOX DEFAULT: Strictly fetch or summarize from user's '").append(folder).append("' folder. ");
            builder.append("By default, emails MUST ONLY be fetched from user's 'inbox' folder. Never fetch from sent, drafts, or other folders unless user specifically requested that folder.\n");
            builder.append("2. Outlook tools use the user's access token so it operates on the user's email ID.\n");
            if (isSummary) {
                builder.append("3. Execute: TOOL_CALL: ").append(findToolName(selectedToolNames, "outlook_summarizeEmails"))
                       .append("(folder: \"").append(folder).append("\", limit: ").append(count)
                       .append(days > 0 ? ", days: " + days : "").append(")\n");
            } else {
                builder.append("3. Execute: TOOL_CALL: ").append(findToolName(selectedToolNames, "outlook_fetchEmails"))
                       .append("(folder: \"").append(folder).append("\", limit: ").append(count)
                       .append(days > 0 ? ", days: " + days : "").append(")\n");
            }
            builder.append("4. Present the emails clearly with Subject, From, Date, Severity, and synthesized Summary.\n");
        }

        builder.append(skillContext == null ? "" : skillContext);

        return builder.toString();
    }

    private String loadSystemPrompt(Resource resource) {
        try {
            if (resource != null && resource.exists()) {
                return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            log.warn("Unable to load system prompt file: {}", ex.getMessage());
        }
        return FALLBACK_SYSTEM_PROMPT;
    }

    private String buildCopilotPromptWithSystem(String prompt) {
        return "[System]\n" + systemPrompt + "\n\n[User]\n" + prompt;
    }

    private boolean isGreeting(String prompt) {
        if (prompt == null) {
            return false;
        }
        String value = prompt.trim().toLowerCase(Locale.ROOT);
        while (value.endsWith("!") || value.endsWith(".") || value.endsWith("?")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value.equals("hi") || value.equals("hello") || value.equals("hey") || value.equals("hii")
                || value.equals("hi there") || value.equals("hello there") || value.equals("hey there")
                || value.equals("good morning") || value.equals("good afternoon") || value.equals("good evening")
                || value.equals("greetings");
    }

    private boolean isJiraRequest(String prompt) {
        String value = prompt == null ? "" : prompt.toLowerCase();
        return containsAny(value, "jira", "ticket", "issue", "jql", "backlog", "sprint");
    }

    private boolean isGitRequest(String prompt) {
        String value = prompt == null ? "" : prompt.toLowerCase();
        return GIT_WORD_PATTERN.matcher(value).find();
    }

    private boolean isConfluenceRequest(String prompt) {
        String value = prompt == null ? "" : prompt.toLowerCase();
        return containsAny(value, "confluence", "wiki", "space", "page", "knowledge", "documentation", "docs");
    }

    static boolean isKafkaRequest(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String p = prompt.toLowerCase(Locale.ROOT);
        return containsAny(p, "kafka", "connector", "connectors", "sinkconnector", "sourceconnector", "restart task", "restart connector");
    }

    public static boolean isServiceNowRequest(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String p = prompt.toLowerCase(Locale.ROOT);
        if (containsAny(p, "servicenow", "service-now", "service now", "incident", "incidents",
                "ritm", "sctask", "service request", "service requests", "service task", "service tasks", "catalog task",
                "sla breach", "sla breached", "aging incident", "aging incidents", "ei services global l2")) {
            return true;
        }
        return p.matches(".*\\b(inc|ritm|sctask|req)\\d{4,8}\\b.*");
    }

    public static boolean isReportOrPptRequest(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String p = prompt.toLowerCase(Locale.ROOT);
        return containsAny(p, "ppt", "pptx", "powerpoint", "presentation", "executive report",
                "slide", "slides", "executive summary report", "generate report", "downloadable link", "downloadable ppt");
    }

    public static boolean isOutlookRequest(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String p = prompt.toLowerCase(Locale.ROOT);
        return containsAny(p, "outlook", "mail", "mails", "emails", "email", "mailbox",
                "inbox", "sent items", "sent folder", "drafts folder",
                "major issue reported", "team issues", "choubey", "eiifw", "emhteam");
    }

    public static String extractFolderFromPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "inbox";
        }
        String p = prompt.toLowerCase(Locale.ROOT);
        if (containsAny(p, "sent", "sent items", "sentitems", "outbox")) {
            return "sentitems";
        }
        if (containsAny(p, "draft", "drafts")) {
            return "drafts";
        }
        if (containsAny(p, "archive", "archived")) {
            return "archive";
        }
        if (containsAny(p, "deleted", "deleted items", "deleteditems", "trash")) {
            return "deleteditems";
        }
        if (containsAny(p, "junk", "junk email", "junkemail", "spam")) {
            return "junkemail";
        }
        return "inbox";
    }

    public static int extractCountFromPrompt(String prompt, int defaultCount) {
        if (prompt == null || prompt.isBlank()) {
            return defaultCount;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:last|latest|top|fetch|show|get|recent)?\\s*(\\d+)\\s*(?:mail|mails|email|emails|messages?)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(prompt);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("(?:last|latest|top)\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(prompt);
        if (m2.find()) {
            try {
                return Integer.parseInt(m2.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return defaultCount;
    }

    public static boolean isJfrogRequest(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String p = prompt.toLowerCase(Locale.ROOT);
        return containsAny(p, "jfrog", "artifactory", "xray", "vulnerability", "vulnerabilities",
                "cve", "cves", "docker image", "docker images", "image tags", "scan image", "vuln");
    }

    public static int extractDaysFromPrompt(String prompt, int defaultDays) {
        if (prompt == null || prompt.isBlank()) {
            return defaultDays;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)\\s*(?:day|days|d\\b)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(prompt);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        if (prompt.toLowerCase(Locale.ROOT).contains("week")) {
            return 7;
        }
        if (prompt.toLowerCase(Locale.ROOT).contains("month")) {
            return 30;
        }
        return defaultDays;
    }

    private boolean isPaginationRequest(String prompt) {
        return JiraPaging.isPagination(prompt);
    }

    private static boolean isJiraSearchTool(String toolName) {
        return toolName != null && toolName.endsWith("jira_searchIssues");
    }

    /** Remembers the JQL, startAt and page size of every Jira search the model runs, so "next 25" can continue it. */
    private void trackJiraSearch(String sessionId, ToolInvocation invocation) {
        if (!isJiraSearchTool(invocation.toolName)) {
            return;
        }
        try {
            String json = JiraPaging.sanitizeSearchArguments(objectMapper, normalizeToolParameters(invocation.parameters));
            JiraPaging.State state = JiraPaging.stateOf(objectMapper, json);
            if (state != null) {
                jiraLastSearch.put(sessionId, state);
                lastIntentBySession.put(sessionId, "jira");
            }
        } catch (RuntimeException ex) {
            log.debug("Session [{}]: could not track Jira search: {}", sessionId, ex.getMessage());
        }
    }

    /**
     * Answers "next 25", "next 10", "page 3", "previous page", "show more" after a Jira list by running the previous
     * search again with the next startAt. Returns null when the prompt is not a paging request, nothing was searched
     * before, or the Jira tool is unavailable (the normal flow then continues).
     */
    private PromptResponse tryJiraPage(String sessionId, PromptRequest request) {
        String prompt = request.getPrompt();
        if (!JiraPaging.isPagination(prompt)) {
            return null;
        }
        JiraPaging.State previous = jiraLastSearch.get(sessionId);
        if (previous == null || !"jira".equals(lastIntentBySession.get(sessionId))) {
            return null;
        }
        Optional<ToolCallback> searchTool = currentMcpTools().stream()
                .filter(tool -> isJiraSearchTool(tool.getToolDefinition().name()))
                .findFirst();
        if (searchTool.isEmpty()) {
            return null;
        }

        int size = JiraPaging.requestedSize(prompt).orElse(previous.pageSize());
        int startAt = JiraPaging.resolveStart(previous, prompt, size);
        String toolName = searchTool.get().getToolDefinition().name();
        String answer;
        List<String> used = Collections.emptyList();
        try {
            com.fasterxml.jackson.databind.node.ObjectNode args = objectMapper.createObjectNode();
            args.put("jql", previous.jql());
            args.put("startAt", startAt);
            args.put("maxResults", size);
            args.putArray("fields").add("summary").add("status").add("assignee").add("priority").add("updated");
            log.info("Session [{}]: Jira paging - startAt={}, maxResults={}, jql={}", sessionId, startAt, size, previous.jql());
            String raw = searchTool.get().call(objectMapper.writeValueAsString(args));
            com.fasterxml.jackson.databind.JsonNode result = JiraPaging.findSearchResult(objectMapper, raw);
            if (result == null) {
                String detail = raw == null ? "no response" : abbreviate(raw, 400);
                log.warn("Session [{}]: Jira paging returned no issues object: {}", sessionId, detail);
                return null;
            }
            jiraLastSearch.put(sessionId, new JiraPaging.State(previous.jql(), startAt, size));
            answer = JiraPaging.render(result, startAt, size, jiraBaseUrl);
            used = List.of(toolName);
        } catch (Exception ex) {
            log.warn("Session [{}]: Jira paging failed: {}", sessionId, ex.getMessage());
            answer = "I could not load the next Jira page: " + ex.getMessage();
        }

        chatMemoryService.addAssistantMessage(sessionId, answer);
        return PromptResponse.builder()
                .response(answer)
                .sessionId(sessionId)
                .provider("EMH AI ASSISTANT")
                .model(configuredModel)
                .toolsUsed(used)
                .toolResults(Collections.emptyList())
                .timestamp(Instant.now())
                .build();
    }

    private String buildJiraJqlFromPrompt(String prompt) {
        String value = prompt == null ? "" : prompt.toLowerCase();
        List<String> statusFilters = new ArrayList<>();

        addStatusIfPresent(value, statusFilters, "Open");
        addStatusIfPresent(value, statusFilters, "In Progress");
        addStatusIfPresent(value, statusFilters, "In Review");
        addStatusIfPresent(value, statusFilters, "To Do");
        addStatusIfPresent(value, statusFilters, "Blocked");
        addStatusIfPresent(value, statusFilters, "Done");
        addStatusIfPresent(value, statusFilters, "Closed");
        addStatusIfPresent(value, statusFilters, "Cancelled");
        addStatusIfPresent(value, statusFilters, "In Production");

        StringBuilder jql = new StringBuilder();
        String projectOrComponentClause = buildProjectOrComponentClause(value);
        jql.append(projectOrComponentClause);

        // Status filtering
        if (statusFilters.isEmpty()) {
            jql.append(" AND status NOT IN (\"Closed\", \"In Production\", \"Cancelled\")");
        } else {
            jql.append(" AND status IN (")
                    .append(statusFilters.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")))
                    .append(")");
        }

        String finalJql = jql.toString();
        log.info("Generated Jira JQL Query: {}", finalJql);
        return finalJql;
    }

    /**
     * Build project or component clause based on user intent.
     * Prioritizes project key search (e.g., project = EMH) over component/label search.
     */
    /**
     * Build project or component clause based on user intent.
     * Format: (component in (...) OR labels in (...))
     * Prioritizes project key search (e.g., project = EMH) as first fallback candidate.
     */
    private String buildProjectOrComponentClause(String prompt) {
        // Check for EMH/Middleware keywords and use Components/label format (matching user's Jira instance)
        if (containsAny(prompt, "emh", "middleware", "energy management")) {
            return "(Components in (\"EMH\", \"EMH-Middleware\") OR labels in (\"EMH\", \"EMH-Middleware\"))";
        }
        // Check for specific project key keywords
        if (containsAny(prompt, "payment")) {
            return "(Components in (\"payment\") OR labels in (\"payment\"))";
        }
        if (containsAny(prompt, "auth", "authentication")) {
            return "(Components in (\"auth\") OR labels in (\"auth\"))";
        }
        if (containsAny(prompt, "database", "db")) {
            return "(Components in (\"database\") OR labels in (\"database\"))";
        }
        if (containsAny(prompt, "api", "interface")) {
            return "(Components in (\"api\") OR labels in (\"api\"))";
        }
        if (containsAny(prompt, "gateway")) {
            return "(Components in (\"gateway\") OR labels in (\"gateway\"))";
        }
        if (containsAny(prompt, "service")) {
            return "(Components in (\"service\") OR labels in (\"service\"))";
        }
        if (containsAny(prompt, "core")) {
            return "(Components in (\"core\") OR labels in (\"core\"))";
        }
        if (containsAny(prompt, "web", "frontend", "ui")) {
            return "(Components in (\"web\") OR labels in (\"web\"))";
        }
        if (containsAny(prompt, "mobile", "app")) {
            return "(Components in (\"mobile\") OR labels in (\"mobile\"))";
        }

        return "(Components in (\"EMH\", \"EMH-Middleware\") OR labels in (\"EMH\", \"EMH-Middleware\"))";
    }

    private void addStatusIfPresent(String prompt, List<String> statusFilters, String status) {
        String normalized = status.toLowerCase();
        if (prompt.contains(normalized) && !statusFilters.contains(status)) {
            statusFilters.add(status);
        }
    }
}
