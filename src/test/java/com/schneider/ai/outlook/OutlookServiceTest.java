package com.schneider.ai.outlook;

import com.schneider.ai.auth.SessionAuthService;
import com.schneider.ai.service.AiOrchestrationService;
import com.schneider.ai.tools.OutlookTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OutlookServiceTest {

    private OutlookProperties properties;
    private OutlookService outlookService;

    @BeforeEach
    void setUp() {
        properties = new OutlookProperties();
        properties.setUserEmail("kamleshkumar.choubey@se.com");
        properties.setTeamEmail("EIIFW.EMHTeam@se.com");
        properties.setMonitoredEmails("kamleshkumar.choubey@se.com,EIIFW.EMHTeam@se.com");

        outlookService = new OutlookService(properties);
    }

    @Test
    void testGetEmails_StrictMonitoredAccountsFilter() {
        List<Map<String, Object>> emails = outlookService.getEmails(7, null, 25);
        assertNotNull(emails);
        assertFalse(emails.isEmpty(), "Should retrieve simulated enterprise emails for 7-day window");

        // Verify that EVERY email is strictly to or from kamleshkumar.choubey@se.com or EIIFW.EMHTeam@se.com
        for (Map<String, Object> email : emails) {
            String sender = String.valueOf(email.get("sender"));
            String to = String.valueOf(email.get("to"));
            String cc = String.valueOf(email.get("cc"));

            boolean matchesMonitored = properties.isMonitoredParticipant(sender, to, cc);
            assertTrue(matchesMonitored,
                    "Every email must be communicated to/from kamleshkumar.choubey@se.com or EIIFW.EMHTeam@se.com. Found: sender=" + sender + ", to=" + to);

            // Must NOT be the third-party newsletter
            assertFalse(sender.contains("newsletter@external-cloud-digest.com"),
                    "Third party unrelated email must be strictly filtered out");
        }
    }

    @Test
    void testGetEmails_FiveDaysWindow() {
        List<Map<String, Object>> emails = outlookService.getEmails(5, null, 25);
        assertNotNull(emails);
        assertFalse(emails.isEmpty(), "Should retrieve emails within 5-day window");

        for (Map<String, Object> email : emails) {
            assertNotNull(email.get("body"), "Email must contain full body");
            assertNotNull(email.get("subject"), "Email must contain subject");
            assertNotNull(email.get("sender"), "Email must contain sender");
        }
    }

    @Test
    void testGetEmails_QueryFilter() {
        List<Map<String, Object>> kafkaEmails = outlookService.getEmails(7, "Kafka", 10);
        assertNotNull(kafkaEmails);
        assertFalse(kafkaEmails.isEmpty());
        for (Map<String, Object> email : kafkaEmails) {
            String subject = (String) email.get("subject");
            String body = (String) email.get("body");
            String team = (String) email.get("team");
            boolean match = (subject != null && subject.toLowerCase().contains("kafka"))
                    || (body != null && body.toLowerCase().contains("kafka"))
                    || (team != null && team.toLowerCase().contains("kafka"));
            assertTrue(match, "Email should match query 'Kafka'");
        }
    }

    @Test
    void testSummarizeEmails_BasedOnMailBodyAndSubject() {
        Map<String, Object> summary = outlookService.summarizeEmails(5, null, 10, null);
        assertNotNull(summary);
        assertEquals("SUCCESS", summary.get("status"));
        assertEquals(5, summary.get("days"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summaries = (List<Map<String, Object>>) summary.get("emailSummaries");
        assertNotNull(summaries);
        assertFalse(summaries.isEmpty());

        for (Map<String, Object> s : summaries) {
            assertNotNull(s.get("subjectSummary"), "Summary must include subject");
            assertNotNull(s.get("bodySummary"), "Summary must include synthesized body summary");
        }

        String formatted = (String) summary.get("formattedSummary");
        assertNotNull(formatted);
        assertTrue(formatted.contains("kamleshkumar.choubey@se.com"));
        assertTrue(formatted.contains("EIIFW.EMHTeam@se.com"));
        assertTrue(formatted.contains("Subject"));
        assertTrue(formatted.contains("Body Summary"));
    }

    @Test
    void testSummarizeMajorIssues_FiveDays() {
        Map<String, Object> summary = outlookService.summarizeMajorIssues(5);
        assertNotNull(summary);
        assertEquals("SUCCESS", summary.get("status"));
        assertEquals(5, summary.get("timeframeDays"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> majorIssues = (List<Map<String, Object>>) summary.get("majorIssues");
        assertNotNull(majorIssues);
        assertFalse(majorIssues.isEmpty(), "Expected major issues flagged within 5 days");

        int count = ((Number) summary.get("majorIssuesCount")).intValue();
        assertEquals(majorIssues.size(), count);

        // Verify structure of first issue
        Map<String, Object> first = majorIssues.get(0);
        assertTrue(first.containsKey("team"));
        assertTrue(first.containsKey("severity"));
        assertTrue(first.containsKey("summary"));
        assertTrue(first.containsKey("actionTaken"));
        assertTrue(first.containsKey("bodyExcerpt"));
    }

    @Test
    void testGetEmails_StrictInboxDefault_ExcludesSentAndDrafts() {
        // By default (folder = null or "inbox"), only INBOX emails should be returned.
        List<Map<String, Object>> inboxEmails = outlookService.getEmails(10, null, null, null, null);
        assertNotNull(inboxEmails);
        assertFalse(inboxEmails.isEmpty());

        for (Map<String, Object> mail : inboxEmails) {
            assertEquals("inbox", mail.get("folder"), "All emails must strictly belong to inbox folder");
            String subject = String.valueOf(mail.get("subject"));
            assertFalse(subject.contains("WARNING: Memory threshold exceeded"), "Sent emails must NOT be returned in inbox");
            assertFalse(subject.contains("DRAFT:"), "Draft emails must NOT be returned in inbox");
        }
    }

    @Test
    void testGetEmails_SentFolderExplicit() {
        // When user explicitly specifies "sent", it should fetch from sent folder
        List<Map<String, Object>> sentEmails = outlookService.getEmails(10, null, null, "sent", null);
        assertNotNull(sentEmails);
        assertFalse(sentEmails.isEmpty(), "Should retrieve sent emails when explicitly requested");

        for (Map<String, Object> mail : sentEmails) {
            assertEquals("sentitems", mail.get("folder"));
            String subject = String.valueOf(mail.get("subject"));
            assertTrue(subject.contains("WARNING: Memory threshold exceeded"), "Sent folder should contain user sent email");
        }
    }

    @Test
    void testGetEmails_DraftsFolderExplicit() {
        // When user explicitly specifies "drafts", it should fetch from drafts folder
        List<Map<String, Object>> draftEmails = outlookService.getEmails(10, null, null, "drafts", null);
        assertNotNull(draftEmails);
        assertFalse(draftEmails.isEmpty(), "Should retrieve draft emails when explicitly requested");

        for (Map<String, Object> mail : draftEmails) {
            assertEquals("drafts", mail.get("folder"));
            String subject = String.valueOf(mail.get("subject"));
            assertTrue(subject.contains("DRAFT:"), "Draft folder should contain draft email");
        }
    }

    @Test
    void testLast5Emails_LimitFive() {
        List<Map<String, Object>> last5 = outlookService.getEmails(5, null, null, "inbox", null);
        assertNotNull(last5);
        assertTrue(last5.size() <= 5, "Must return at most 5 emails");
        for (Map<String, Object> mail : last5) {
            assertEquals("inbox", mail.get("folder"));
            assertNotNull(mail.get("userEmail"));
        }
    }

    @Test
    void testSummarizeLast5Emails() {
        Map<String, Object> summary = outlookService.summarizeEmails(5, null, null, "inbox", null);
        assertNotNull(summary);
        assertEquals("SUCCESS", summary.get("status"));
        assertEquals("inbox", summary.get("folder"));
        assertNotNull(summary.get("userEmail"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summaries = (List<Map<String, Object>>) summary.get("emailSummaries");
        assertNotNull(summaries);
        assertTrue(summaries.size() <= 5);
    }

    @Test
    void testResolveUserEmailFromJwt() {
        // Create dummy JWT token with claims
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"upn\":\"alex.developer@se.com\",\"name\":\"Alex Developer\"}".getBytes(StandardCharsets.UTF_8));
        String dummyJwt = header + "." + payload + ".dummySig";

        java.util.Optional<String> extracted = SessionAuthService.extractEmailFromJwt(dummyJwt);
        assertTrue(extracted.isPresent());
        assertEquals("alex.developer@se.com", extracted.get());
    }

    @Test
    void testOutlookTools_InboxByDefault() {
        OutlookTools tools = new OutlookTools(outlookService);
        List<Map<String, Object>> emails = tools.fetchEmails(null, 5, null, null);
        assertNotNull(emails);
        assertFalse(emails.isEmpty());
        for (Map<String, Object> mail : emails) {
            assertEquals("inbox", mail.get("folder"), "OutlookTools must fetch from inbox by default");
        }

        Map<String, Object> summary = tools.summarizeEmails(null, 5, null, null);
        assertEquals("inbox", summary.get("folder"));
    }

    @Test
    void testAiOrchestrationHelpers() {
        assertEquals("inbox", AiOrchestrationService.extractFolderFromPrompt("show me the last 5 mail"));
        assertEquals("inbox", AiOrchestrationService.extractFolderFromPrompt("summarize it"));
        assertEquals("sentitems", AiOrchestrationService.extractFolderFromPrompt("show me the last 5 mails from sent"));
        assertEquals("sentitems", AiOrchestrationService.extractFolderFromPrompt("check sent items"));
        assertEquals("drafts", AiOrchestrationService.extractFolderFromPrompt("list draft emails"));

        assertEquals(5, AiOrchestrationService.extractCountFromPrompt("show me the last 5 mail", 10));
        assertEquals(10, AiOrchestrationService.extractCountFromPrompt("summarize latest 10 emails", 5));
        assertEquals(3, AiOrchestrationService.extractCountFromPrompt("show 3 mails", 5));
        assertEquals(5, AiOrchestrationService.extractCountFromPrompt("show emails", 5));

        assertTrue(AiOrchestrationService.isOutlookRequest("show me the last 5 mail"));
        assertTrue(AiOrchestrationService.isOutlookRequest("check my mailbox"));
        assertTrue(AiOrchestrationService.isOutlookRequest("check inbox"));
    }
}
