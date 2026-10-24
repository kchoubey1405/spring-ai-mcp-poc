package com.schneider.ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AiOrchestrationServiceAgentTest {

    private static final String PUSH = "spring_ai_mcp_client_git_mcp_server_push_files";
    private static final String PR = "spring_ai_mcp_client_git_mcp_server_create_pull_request";
    private static final String MERGE = "spring_ai_mcp_client_git_mcp_server_merge_pull_request";
    private static final String GET_ISSUE = "spring_ai_mcp_client_jira_mcp_server_jira_getIssue";

    private AiOrchestrationService service;

    @BeforeEach
    void setUp() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.refresh();
        service = new AiOrchestrationService(null, context, null, null, "gpt-5", "ifw-microservices",
                new ByteArrayResource("system".getBytes()));
    }

    private <T> T call(String method, Object... args) {
        return ReflectionTestUtils.invokeMethod(service, method, args);
    }

    @Test
    void extractsIssueKeyAndIgnoresNonIssueTokens() {
        assertEquals("CMDM-8449", call("extractIssueKey", "make the change for CMDM-8449 and raise a PR"));
        assertNull(call("extractIssueKey", "the file encoding is UTF-8"));
        assertNull(call("extractIssueKey", "how does phone validation work"));
    }

    @Test
    void detectsChangeRequests() {
        assertEquals(Boolean.TRUE, call("isChangeRequest", "Make the change for CMDM-8449 and raise the pull request"));
        assertEquals(Boolean.TRUE, call("isChangeRequest", "implement INTSOL-123"));
        assertEquals(Boolean.TRUE, call("isChangeRequest", "raise a PR for this"));
        assertEquals(Boolean.FALSE, call("isChangeRequest", "show me open PRs"));
        assertEquals(Boolean.FALSE, call("isChangeRequest", "what is CMDM-8449 about"));
    }

    @Test
    void gitWordDetectionIsNotSubstringBased() {
        assertEquals(Boolean.FALSE, call("isGitRequest", "tell me about the project status"));
        assertEquals(Boolean.TRUE, call("isGitRequest", "list open pr in mdm-integration"));
    }

    @Test
    void parsesMultiLineJsonToolCall() {
        String response = "Doing it now\n"
                + "TOOL_CALL: " + PUSH + "({\n"
                + "  \"owner\": \"ifw-microservices\",\n"
                + "  \"repo\": \"mdm-integration\",\n"
                + "  \"branch\": \"feature/CMDM-1\",\n"
                + "  \"message\": \"CMDM-1: fix (test)\",\n"
                + "  \"files\": [{\"path\": \"a.txt\", \"content\": \"x\"}]\n"
                + "})\n"
                + "TOOL_CALL: " + GET_ISSUE + "(issueKey: CMDM-1)\n";
        List<Object> invocations = call("detectToolInvocations", response, Set.of(PUSH, GET_ISSUE));
        assertEquals(2, invocations.size());
        assertEquals(PUSH, ReflectionTestUtils.getField(invocations.get(0), "toolName"));
        String params = (String) ReflectionTestUtils.getField(invocations.get(0), "parameters");
        assertTrue(params.startsWith("{") && params.endsWith("}"));
        assertEquals(GET_ISSUE, ReflectionTestUtils.getField(invocations.get(1), "toolName"));
    }

    @Test
    void normalizesTypedParameters() {
        String json = call("normalizeToolParameters", "owner: ifw, repo: mdm-integration, pullNumber: 12, draft: true");
        assertTrue(json.contains("\"pullNumber\":12"), json);
        assertTrue(json.contains("\"draft\":true"), json);
        assertTrue(json.contains("\"owner\":\"ifw\""), json);
        assertEquals("{\"issueKey\":\"CMDM-1\"}", call("normalizeToolParameters", "issueKey: CMDM-1"));
    }

    @Test
    void repairsRawNewlinesInsideJsonStrings() {
        String repaired = call("repairJsonControlChars", "{\"content\":\"a\nb\"}");
        assertEquals("{\"content\":\"a\\nb\"}", repaired);
    }

    @Test
    void guardsBlockUnsafeWrites() {
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "guardToolCall", MERGE, "{}"));
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "guardToolCall", PUSH, "{\"branch\":\"develop\"}"));
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "guardToolCall", PUSH, "{\"branch\":\"refs/heads/master\"}"));
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "guardToolCall", PR, "{\"head\":\"main\",\"base\":\"develop\"}"));
        assertDoesNotThrow(
                () -> ReflectionTestUtils.invokeMethod(service, "guardToolCall", PUSH, "{\"branch\":\"feature/CMDM-1\"}"));
        assertDoesNotThrow(
                () -> ReflectionTestUtils.invokeMethod(service, "guardToolCall", PR, "{\"head\":\"feature/CMDM-1\",\"base\":\"develop\"}"));
    }

    @Test
    void parenDepthIgnoresQuotedParentheses() {
        assertEquals(Integer.valueOf(0), call("parenDepth", "TOOL_CALL: x({\"a\":\"(\"})"));
        assertEquals(Integer.valueOf(1), call("parenDepth", "TOOL_CALL: x({"));
    }

    @Test
    void recognizesExtendedGreetings() {
        assertEquals(Boolean.TRUE, call("isGreeting", "hi"));
        assertEquals(Boolean.TRUE, call("isGreeting", "hello!"));
        assertEquals(Boolean.TRUE, call("isGreeting", "hi there"));
        assertEquals(Boolean.TRUE, call("isGreeting", "Good morning"));
        assertEquals(Boolean.TRUE, call("isGreeting", "hey there!"));
        assertEquals(Boolean.FALSE, call("isGreeting", "show me open PRs"));
    }

    @Test
    void knowledgeRequestOnlyTriggersOnConfluenceOrDomainKeywords() {
        // General conversational queries must NOT trigger knowledge/Confluence intent
        assertEquals(Boolean.FALSE, call("isKnowledgeRequest", "what can you do?"));
        assertEquals(Boolean.FALSE, call("isKnowledgeRequest", "who are you?"));
        assertEquals(Boolean.FALSE, call("isKnowledgeRequest", "how are you today?"));
        assertEquals(Boolean.FALSE, call("isKnowledgeRequest", "explain polymorphism in Java"));

        // Enterprise documentation and domain queries MUST trigger knowledge/Confluence intent
        assertEquals(Boolean.TRUE, call("isKnowledgeRequest", "what is the confluence page for kafka"));
        assertEquals(Boolean.TRUE, call("isKnowledgeRequest", "find documentation for release process"));
        assertEquals(Boolean.TRUE, call("isKnowledgeRequest", "explain the EMH connector architecture"));
    }

    @Test
    void generalPromptDoesNotArmEnterpriseTools() {
        List<Object> tools = call("resolveAgentToolsByIntent", "what can you do?", new String[0]);
        assertTrue(tools.isEmpty(), "General conversational queries should not arm tools");
    }

    @Test
    void changeRequestArmsToolsAndInstructsBranchFromDevelopFirst() {
        String prompt = ReflectionTestUtils.invokeMethod(service, "buildAgentPromptWithToolDirective",
                "make the change for CMDM-8449 and raise PR",
                "make the change for CMDM-8449 and raise PR",
                new java.util.ArrayList<String>(),
                "",
                (String) null,
                (String) null);
        assertTrue(prompt.contains("feature/CMDM-8449 from develop"));
        assertTrue(prompt.contains("DO CODE ANALYSIS IN THE FEATURE BRANCH ONLY"));
        assertTrue(prompt.contains("NEVER refer to master as a base branch"));
        assertTrue(prompt.contains("TAKE CONSENT"));
    }

    @Test
    void userApprovalInstructsCompileTestSpringBootRunAndPR() {
        String prompt = ReflectionTestUtils.invokeMethod(service, "buildAgentPromptWithToolDirective",
                "make the change for CMDM-8449 and raise PR\naccept",
                "accept",
                new java.util.ArrayList<String>(),
                "",
                (String) null,
                (String) null);
        assertTrue(prompt.contains("User Approved"));
        assertTrue(prompt.contains("clean test"));
        assertTrue(prompt.contains("spring-boot:run"));
        assertTrue(prompt.contains("develop"));
        assertFalse(prompt.contains("[Confluence Task]"));
    }

    @Test
    void detectsUserConfirmationOfProposedChange() {
        // Enriched UI prompt
        String enrichedPrompt = "CMDM-8449: Make changes. The proposed code changes and test cases are approved. Apply the finalized changes directly to the feature branch, compile and test with Maven, verify with spring-boot:run, and raise the pull request to merge into develop. Do not re-clone, re-branch, or re-analyze the code.";
        assertEquals(Boolean.TRUE, call("isConfirmChange", enrichedPrompt, java.util.Collections.emptyList()));

        // Simple 'accept' with prior diff proposal in history
        org.springframework.ai.chat.messages.AssistantMessage proposalMsg =
                new org.springframework.ai.chat.messages.AssistantMessage("Here are the proposed file changes:\n```diff\n--- a/App.java\n+++ b/App.java\n```\nReply with 'accept' to apply.");
        org.springframework.ai.chat.messages.UserMessage userMsg =
                new org.springframework.ai.chat.messages.UserMessage("accept");
        java.util.List<org.springframework.ai.chat.messages.Message> historyWithProposal = java.util.List.of(proposalMsg, userMsg);

        assertEquals(Boolean.TRUE, call("isConfirmChange", "accept", historyWithProposal));
        assertEquals(Boolean.TRUE, call("isConfirmChange", "yes, please go ahead", historyWithProposal));
        assertEquals(Boolean.TRUE, call("isConfirmChange", "lgtm", historyWithProposal));
        assertEquals(Boolean.TRUE, call("isConfirmChange", "approved, raise pr", historyWithProposal));

        // Initial request should NOT be detected as confirmation
        assertEquals(Boolean.FALSE, call("isConfirmChange", "make the change for CMDM-8449 and raise PR", java.util.Collections.emptyList()));
        assertEquals(Boolean.FALSE, call("isConfirmChange", "show open PRs", java.util.Collections.emptyList()));
    }

    @Test
    void vulnerabilityFixWorkflowInstructsBranchFromDevelopFirstAndTakeConsent() {
        String prompt = ReflectionTestUtils.invokeMethod(service, "buildAgentPromptWithToolDirective",
                "Fix vulnerabilities in mdm-integration release 1.90.0 and raise pull request",
                "Fix vulnerabilities in mdm-integration release 1.90.0 and raise pull request",
                new java.util.ArrayList<String>(),
                "",
                (String) null,
                "fix");
        assertTrue(prompt.contains("Vulnerability Fix Task"));
        assertTrue(prompt.contains("feature/vulnerability-fix-mdm-integration-<release> from develop"));
        assertTrue(prompt.contains("DO CODE AND POM ANALYSIS IN THE FEATURE BRANCH ONLY"));
        assertTrue(prompt.contains("NEVER refer to master as a base branch"));
        assertTrue(prompt.contains("clean install"));
        assertTrue(prompt.contains("TAKE CONSENT"));
    }

    @Test
    void vulnerabilityFixApprovalInstructsCompileTestSpringBootRunAndPR() {
        String prompt = ReflectionTestUtils.invokeMethod(service, "buildAgentPromptWithToolDirective",
                "Fix vulnerabilities in mdm-integration release 1.90.0\naccept",
                "accept",
                new java.util.ArrayList<String>(),
                "",
                (String) null,
                "fix",
                true);
        assertTrue(prompt.contains("User Approved"));
        assertTrue(prompt.contains("clean test"));
        assertTrue(prompt.contains("spring-boot:run"));
        assertTrue(prompt.contains("develop"));
        assertFalse(prompt.contains("[Confluence Task]"));
    }
}
