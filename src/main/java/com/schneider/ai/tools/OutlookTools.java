package com.schneider.ai.tools;

import com.schneider.ai.auth.SessionAuthService;
import com.schneider.ai.outlook.OutlookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Spring AI Tools for Microsoft Outlook email intelligence and operational issues summarization.
 *
 * Rules:
 * - STRICT INBOX DEFAULT: Strictly fetches or summarizes from user's 'inbox' folder by default.
 * - Only queries 'sent', 'drafts', or other folders when explicitly requested by user.
 * - Uses the user's email ID dynamically resolved from their Outlook access token.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutlookTools {

    private final OutlookService outlookService;

    @Autowired(required = false)
    private SessionAuthService sessionAuthService;

    private String getSessionId() {
        return sessionAuthService != null ? sessionAuthService.getCurrentSessionId() : null;
    }

    @Tool(name = "outlook_fetchEmails", description = """
            Fetch latest emails from user's Outlook mailbox.
            STRICT INBOX DEFAULT: Strictly fetches emails from user's 'inbox' folder by default (e.g. for 'show me the last 5 mail').
            Only fetches from 'sent', 'drafts', or other folders if user specifically requests that folder.
            Uses the user's email ID dynamically resolved from their Outlook access token.
            """)
    public List<Map<String, Object>> fetchEmails(
            @ToolParam(required = false, description = "Mailbox folder to fetch from: 'inbox' (default), 'sent', 'drafts', 'archive', etc. Strictly defaults to 'inbox' unless user specifies otherwise.") String folder,
            @ToolParam(required = false, description = "Maximum number of emails to return (e.g. 5 for last 5 emails). Default: 10") Integer limit,
            @ToolParam(required = false, description = "Optional number of past days to scan (e.g. 5, 7, 14). If user asks for last N emails, limit should be used.") Integer days,
            @ToolParam(required = false, description = "Search query keyword in subject, body, or team") String query) {
        log.info("Tool outlook_fetchEmails invoked (folder={}, limit={}, days={}, query={})", folder, limit, days, query);
        return outlookService.getEmails(limit, days, query, folder, getSessionId());
    }

    @Tool(name = "outlook_summarizeEmails", description = """
            Provide a comprehensive operational summary of latest emails from user's Outlook mailbox.
            STRICT INBOX DEFAULT: Strictly summarizes emails from user's 'inbox' folder by default (e.g. when user asks to summarize the last 5 emails or 'summarize it').
            Only summarizes from 'sent', 'drafts', or other folders if user specifically requests that folder.
            Uses the user's email ID dynamically resolved from their Outlook access token.
            """)
    public Map<String, Object> summarizeEmails(
            @ToolParam(required = false, description = "Mailbox folder to summarize from: 'inbox' (default), 'sent', 'drafts', 'archive', etc. Strictly defaults to 'inbox' unless user specifies otherwise.") String folder,
            @ToolParam(required = false, description = "Maximum number of emails to summarize (e.g. 5 for last 5 emails). Default: 10") Integer limit,
            @ToolParam(required = false, description = "Optional number of past days to scan (e.g. 5, 7, 14). Default: 7") Integer days,
            @ToolParam(required = false, description = "Optional query or topic keyword") String query) {
        log.info("Tool outlook_summarizeEmails invoked (folder={}, limit={}, days={}, query={})", folder, limit, days, query);
        return outlookService.summarizeEmails(limit, days, query, folder, getSessionId());
    }

    @Tool(name = "outlook_summarizeMajorIssues", description = """
            Scan and summarize major operational issues, outages, and team escalations reported via Outlook emails.
            STRICT INBOX DEFAULT: Strictly scans user's 'inbox' folder by default.
            Only scans other folders if user specifically requests them.
            Uses the user's email ID dynamically resolved from their Outlook access token.
            """)
    public Map<String, Object> summarizeMajorIssues(
            @ToolParam(required = false, description = "Mailbox folder to scan: 'inbox' (default), 'sent', 'drafts', etc. Defaults to 'inbox'.") String folder,
            @ToolParam(required = false, description = "Number of past days to scan (e.g. 5, 7, 14, 30). Default: 7") Integer days,
            @ToolParam(required = false, description = "Maximum number of emails to scan. Default: 50") Integer limit) {
        log.info("Tool outlook_summarizeMajorIssues invoked (folder={}, days={}, limit={})", folder, days, limit);
        return outlookService.summarizeMajorIssues(limit, days, folder, getSessionId());
    }
}
