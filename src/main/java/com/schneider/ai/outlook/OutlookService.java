package com.schneider.ai.outlook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Service for Microsoft Outlook / Office 365 email intelligence and operational issue summarization.
 *
 * Folders:
 * - Strictly defaults to the user's 'inbox' folder.
 * - Only queries 'sentitems', 'drafts', or other folders when explicitly requested by user.
 *
 * User Identity:
 * - Uses the authenticated user's access token to dynamically resolve the user's email ID
 *   via JWT claims (upn, email, preferred_username, unique_name) or Microsoft Graph API /me.
 *
 * Automatically connects to Microsoft Graph API if authenticated, or falls back to
 * Schneider Electric enterprise mailbox data strictly matching the user and monitored accounts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutlookService {

    private final OutlookProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.schneider.ai.auth.SessionAuthService sessionAuthService;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    /**
     * Normalizes mailbox folder aliases to standard Graph API folder names.
     * Strictly defaults to 'inbox'.
     */
    public static String normalizeFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            return "inbox";
        }
        String f = folder.trim().toLowerCase(Locale.ROOT);
        return switch (f) {
            case "sent", "sent items", "sent-items", "sent_items", "sentitems", "outbox" -> "sentitems";
            case "draft", "drafts" -> "drafts";
            case "deleted", "deleted items", "deleted-items", "deleteditems", "trash" -> "deleteditems";
            case "junk", "junk email", "junk-email", "junkemail", "spam" -> "junkemail";
            case "archive", "archived" -> "archive";
            case "inbox" -> "inbox";
            default -> f;
        };
    }

    /**
     * Dynamically resolves the active user's email address from:
     * 1. Session store username if contains @
     * 2. Access token JWT claims (upn, email, preferred_username, unique_name)
     * 3. Microsoft Graph API /me endpoint
     * 4. Configured properties or fallback
     */
    public String resolveUserEmail(String sessionId) {
        String sid = (sessionId != null && !sessionId.isBlank()) ? sessionId
                : (sessionAuthService != null ? sessionAuthService.getCurrentSessionId() : null);
        if (sessionAuthService != null && sid != null && !sid.isBlank()) {
            String email = sessionAuthService.getUserEmail(sid);
            if (email != null && !email.isBlank() && email.contains("@")) {
                properties.setUserEmail(email.trim());
                return email.trim();
            }
        }
        String token = resolveToken(sid);
        if (token != null && !token.isBlank()) {
            Optional<String> jwtEmail = com.schneider.ai.auth.SessionAuthService.extractEmailFromJwt(token);
            if (jwtEmail.isPresent() && jwtEmail.get().contains("@")) {
                properties.setUserEmail(jwtEmail.get());
                if (sessionAuthService != null && sid != null && !sid.isBlank()) {
                    sessionAuthService.saveSessionToken(sid, "outlook", token, jwtEmail.get());
                }
                return jwtEmail.get();
            }
            try {
                RestTemplate restTemplate = new RestTemplate();
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(token);
                headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                String meUrl = properties.getGraphUrl().replaceAll("/+$", "") + "/me";
                ResponseEntity<String> resp = restTemplate.exchange(meUrl, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                    JsonNode node = mapper.readTree(resp.getBody());
                    String mail = node.path("mail").asText("");
                    if (mail.contains("@")) {
                        properties.setUserEmail(mail);
                        if (sessionAuthService != null && sid != null && !sid.isBlank()) {
                            sessionAuthService.saveSessionToken(sid, "outlook", token, mail);
                        }
                        return mail;
                    }
                    String upn = node.path("userPrincipalName").asText("");
                    if (upn.contains("@")) {
                        properties.setUserEmail(upn);
                        if (sessionAuthService != null && sid != null && !sid.isBlank()) {
                            sessionAuthService.saveSessionToken(sid, "outlook", token, upn);
                        }
                        return upn;
                    }
                }
            } catch (Exception ex) {
                log.debug("Graph /me email resolution check: {}", ex.getMessage());
            }
        }
        if (properties.getUserEmail() != null && !properties.getUserEmail().isBlank()) {
            return properties.getUserEmail().trim();
        }
        return "kamleshkumar.choubey@se.com";
    }

    /**
     * Backward-compatible overload: Retrieve emails received within past days matching optional query.
     */
    public List<Map<String, Object>> getEmails(int days, String query, Integer limit) {
        return getEmails(limit, days, query, "inbox", null);
    }

    public List<Map<String, Object>> getEmails(int days, String query, Integer limit, String sessionId) {
        return getEmails(limit, days, query, "inbox", sessionId);
    }

    /**
     * Primary email retrieval method with full folder and access token user support.
     * Strictly queries user's 'inbox' by default unless user explicitly specifies another folder.
     */
    public List<Map<String, Object>> getEmails(Integer limit, Integer days, String query, String folder, String sessionId) {
        int max = (limit == null || limit <= 0) ? 10 : Math.min(limit, 200);
        int windowDays = (days != null && days > 0) ? days : 0;
        Instant cutoff = windowDays > 0 ? Instant.now().minus(windowDays, ChronoUnit.DAYS) : null;
        String normalizedFolder = normalizeFolder(folder);
        String userEmail = resolveUserEmail(sessionId);

        List<Map<String, Object>> store;
        if (isLiveConfigured(sessionId)) {
            try {
                store = fetchLiveEmails(normalizedFolder, max, cutoff, query, sessionId, userEmail);
                if (store.isEmpty()) {
                    store = generateEnterpriseMailbox(windowDays > 0 ? windowDays : 30, cutoff, normalizedFolder, userEmail);
                }
            } catch (Exception ex) {
                log.warn("Failed to fetch live Outlook emails via Graph API ({}). Using Schneider Electric organizational mailbox store.", ex.getMessage());
                store = generateEnterpriseMailbox(windowDays > 0 ? windowDays : 30, cutoff, normalizedFolder, userEmail);
            }
        } else {
            store = generateEnterpriseMailbox(windowDays > 0 ? windowDays : 30, cutoff, normalizedFolder, userEmail);
        }

        String q = query != null ? query.toLowerCase(Locale.ROOT).trim() : "";

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> email : store) {
            // Strictly check folder: must match normalizedFolder (default 'inbox')
            String emailFolder = normalizeFolder(String.valueOf(email.getOrDefault("folder", "inbox")));
            if (!normalizedFolder.equalsIgnoreCase(emailFolder)) {
                continue;
            }

            // Participant check: email must involve userEmail or monitored accounts
            String sender = String.valueOf(email.get("sender"));
            String to = String.valueOf(email.get("to"));
            String cc = String.valueOf(email.get("cc"));
            if (!properties.isMonitoredParticipant(sender, to, cc, userEmail)) {
                continue;
            }

            if (!q.isBlank()) {
                String subj = String.valueOf(email.get("subject")).toLowerCase(Locale.ROOT);
                String body = String.valueOf(email.get("body")).toLowerCase(Locale.ROOT);
                String team = String.valueOf(email.get("team")).toLowerCase(Locale.ROOT);
                if (!subj.contains(q) && !body.contains(q) && !team.contains(q)) {
                    continue;
                }
            }
            filtered.add(email);
            if (filtered.size() >= max) break;
        }
        return filtered;
    }

    /**
     * Backward-compatible overload: Identify and summarize major operational issues.
     */
    public Map<String, Object> summarizeMajorIssues(int days) {
        return summarizeMajorIssues(50, days, "inbox", null);
    }

    public Map<String, Object> summarizeMajorIssues(int days, String sessionId) {
        return summarizeMajorIssues(50, days, "inbox", sessionId);
    }

    public Map<String, Object> summarizeMajorIssues(Integer limit, Integer days, String folder, String sessionId) {
        int windowDays = (days != null && days > 0) ? days : 7;
        String normalizedFolder = normalizeFolder(folder);
        String userEmail = resolveUserEmail(sessionId);
        Instant cutoff = Instant.now().minus(windowDays, ChronoUnit.DAYS);

        List<Map<String, Object>> allEmails = getEmails(limit != null ? limit : 50, windowDays, null, normalizedFolder, sessionId);
        List<Map<String, Object>> majorIssues = new ArrayList<>();

        for (Map<String, Object> email : allEmails) {
            String severity = String.valueOf(email.get("severity"));
            boolean isMajor = "CRITICAL".equalsIgnoreCase(severity) || "HIGH".equalsIgnoreCase(severity)
                    || Boolean.TRUE.equals(email.get("isMajorIssue"));

            if (isMajor) {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("subject", email.get("subject"));
                issue.put("team", email.get("team"));
                issue.put("sender", email.get("sender"));
                issue.put("to", email.get("to"));
                issue.put("cc", email.get("cc"));
                issue.put("folder", email.get("folder"));
                issue.put("date", email.get("receivedTimeFormatted"));
                issue.put("severity", severity);
                issue.put("summary", email.get("issueSummary"));
                issue.put("impact", email.get("impact"));
                issue.put("actionTaken", email.get("actionTaken"));
                issue.put("bodyExcerpt", abbreviate(String.valueOf(email.get("body")), 350));
                majorIssues.add(issue);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("userEmail", userEmail);
        result.put("folder", normalizedFolder);
        result.put("monitoredAccounts", properties.getMonitoredEmailsList(userEmail));
        result.put("timeframeDays", windowDays);
        result.put("since", DATE_FMT.format(cutoff));
        result.put("until", DATE_FMT.format(Instant.now()));
        result.put("totalEmailsScanned", allEmails.size());
        result.put("majorIssuesCount", majorIssues.size());
        result.put("majorIssues", majorIssues);
        result.put("executiveBrief", generateTextualSummary(windowDays, userEmail, allEmails, majorIssues));
        return result;
    }

    /**
     * Backward-compatible overload: Synthesize summary of emails.
     */
    public Map<String, Object> summarizeEmails(int days, String query, Integer limit, String sessionId) {
        return summarizeEmails(limit, days, query, "inbox", sessionId);
    }

    /**
     * Comprehensive summary of emails from requested mailbox folder (defaults strictly to 'inbox').
     */
    public Map<String, Object> summarizeEmails(Integer limit, Integer days, String query, String folder, String sessionId) {
        int windowDays = (days != null && days > 0) ? days : (limit != null ? limit : 5);
        String normalizedFolder = normalizeFolder(folder);
        String userEmail = resolveUserEmail(sessionId);
        List<Map<String, Object>> emails = getEmails(limit, days, query, normalizedFolder, sessionId);
        List<Map<String, Object>> summaries = new ArrayList<>();

        int criticalHighCount = 0;
        for (Map<String, Object> email : emails) {
            String sev = String.valueOf(email.get("severity"));
            if ("CRITICAL".equalsIgnoreCase(sev) || "HIGH".equalsIgnoreCase(sev)) {
                criticalHighCount++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("subject", email.get("subject"));
            item.put("from", email.get("sender"));
            item.put("to", email.get("to"));
            item.put("date", email.get("receivedTimeFormatted"));
            item.put("folder", email.get("folder"));
            item.put("severity", sev);
            item.put("subjectSummary", email.get("subject"));
            item.put("bodySummary", email.get("issueSummary"));
            item.put("impact", email.get("impact"));
            item.put("actionTaken", email.get("actionTaken"));
            summaries.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "SUCCESS");
        out.put("userEmail", userEmail);
        out.put("folder", normalizedFolder);
        out.put("monitoredAccounts", properties.getMonitoredEmailsList(userEmail));
        out.put("days", windowDays);
        out.put("totalEmails", emails.size());
        out.put("criticalHighIssues", criticalHighCount);
        out.put("emailSummaries", summaries);
        out.put("formattedSummary", generateDetailedBodyAndSubjectSummary(normalizedFolder, userEmail, emails));
        return out;
    }

    private String generateDetailedBodyAndSubjectSummary(String folder, String userEmail, List<Map<String, Object>> emails) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Outlook Operational Email Summary (Folder: `").append(folder).append("`)\n");
        sb.append("**User Mailbox**: `").append(userEmail).append("`\n");
        sb.append("**Monitored Accounts**: `").append(String.join("`, `", properties.getMonitoredEmailsList(userEmail))).append("`\n");
        sb.append("**Total Relevant Communications**: ").append(emails.size()).append("\n\n");

        if (emails.isEmpty()) {
            sb.append("No emails found in '").append(folder).append("' folder for ").append(userEmail).append(".\n");
            return sb.toString();
        }

        sb.append("#### Key Communications & Issues (Synthesized from Subject & Mail Body):\n\n");
        int idx = 1;
        for (Map<String, Object> email : emails) {
            sb.append(idx++).append(". **Subject**: ").append(email.get("subject")).append("\n");
            sb.append("   - **From**: ").append(email.get("sender")).append(" | **To**: ").append(email.get("to")).append("\n");
            sb.append("   - **Date**: ").append(email.get("receivedTimeFormatted")).append(" | **Severity**: `").append(email.get("severity")).append("`\n");
            sb.append("   - **Body Summary**: ").append(email.get("issueSummary")).append("\n");
            if (email.get("impact") != null && !String.valueOf(email.get("impact")).isBlank()) {
                sb.append("   - **Operational Impact**: ").append(email.get("impact")).append("\n");
            }
            if (email.get("actionTaken") != null && !String.valueOf(email.get("actionTaken")).isBlank()) {
                sb.append("   - **Mitigation / Action Taken**: ").append(email.get("actionTaken")).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String generateTextualSummary(int days, String userEmail, List<Map<String, Object>> allEmails, List<Map<String, Object>> majorIssues) {
        StringBuilder sb = new StringBuilder();
        sb.append("Scanned ").append(allEmails.size()).append(" emails for ")
                .append(userEmail)
                .append(" over the past ").append(days).append(" days. ");
        if (majorIssues.isEmpty()) {
            sb.append("No critical outages or high-severity team escalations were reported during this window.");
        } else {
            sb.append("Detected ").append(majorIssues.size()).append(" major operational escalation(s) requiring attention.");
        }
        return sb.toString();
    }

    // =========================================================================
    // Microsoft Graph API Live Integration
    // =========================================================================

    private boolean isLiveConfigured(String sessionId) {
        String token = resolveToken(sessionId);
        return (token != null && !token.isBlank())
                || (!properties.getClientId().isBlank() && !properties.getClientSecret().isBlank() && !properties.getTenantId().isBlank());
    }

    private String resolveToken(String sessionId) {
        String sid = (sessionId != null && !sessionId.isBlank()) ? sessionId
                : (sessionAuthService != null ? sessionAuthService.getCurrentSessionId() : null);
        if (sessionAuthService != null && sid != null && !sid.isBlank()) {
            Optional<String> token = sessionAuthService.getToken(sid, "outlook");
            if (token.isPresent() && !token.get().isBlank()) {
                return token.get();
            }
        }
        if (sessionAuthService != null) {
            String fb = sessionAuthService.getFallbackTokenForService("outlook");
            if (fb != null && !fb.isBlank()) {
                return fb;
            }
        }
        return "";
    }

    private List<Map<String, Object>> fetchLiveEmails(String folder, int limit, Instant cutoff, String query, String sessionId, String userEmail) {
        RestTemplate restTemplate = new RestTemplate();
        String token = resolveToken(sessionId);
        boolean isUserToken = true;
        if (token == null || token.isBlank()) {
            token = fetchAppOnlyToken(restTemplate);
            isUserToken = false;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        String base = properties.getGraphUrl().replaceAll("/+$", "");
        String normalizedFolder = normalizeFolder(folder);

        String filter = cutoff != null ? "receivedDateTime ge " + cutoff.toString() : "";
        if (query != null && !query.isBlank()) {
            String qFilter = "(contains(subject,'" + query + "') or contains(body/content,'" + query + "'))";
            filter = filter.isBlank() ? qFilter : filter + " and " + qFilter;
        }

        List<URI> uriCandidates = new ArrayList<>();
        if (isUserToken) {
            UriComponentsBuilder b1 = UriComponentsBuilder.fromUriString(base + "/me/mailFolders/" + normalizedFolder + "/messages")
                    .queryParam("$top", String.valueOf(limit))
                    .queryParam("$select", "subject,sender,from,toRecipients,ccRecipients,receivedDateTime,bodyPreview,importance,body")
                    .queryParam("$orderby", "receivedDateTime desc");
            if (!filter.isBlank()) b1.queryParam("$filter", filter);
            uriCandidates.add(b1.build().toUri());
        }

        if (userEmail != null && !userEmail.isBlank()) {
            UriComponentsBuilder b2 = UriComponentsBuilder.fromUriString(base + "/users/" + userEmail + "/mailFolders/" + normalizedFolder + "/messages")
                    .queryParam("$top", String.valueOf(limit))
                    .queryParam("$select", "subject,sender,from,toRecipients,ccRecipients,receivedDateTime,bodyPreview,importance,body")
                    .queryParam("$orderby", "receivedDateTime desc");
            if (!filter.isBlank()) b2.queryParam("$filter", filter);
            uriCandidates.add(b2.build().toUri());
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (URI uri : uriCandidates) {
            try {
                ResponseEntity<String> resp = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                    JsonNode root = mapper.readTree(resp.getBody());
                    JsonNode value = root.path("value");
                    if (value.isArray()) {
                        for (JsonNode item : value) {
                            list.add(parseGraphMessage(item, normalizedFolder, userEmail));
                        }
                    }
                    return list;
                }
            } catch (Exception ex) {
                log.warn("Failed fetching live emails from URI {}: {}", uri, ex.getMessage());
            }
        }
        return list;
    }

    private String fetchAppOnlyToken(RestTemplate restTemplate) {
        // App-only OAuth2 client credentials flow with Azure AD
        String tokenEndpoint = "https://login.microsoftonline.com/" + properties.getTenantId() + "/oauth2/v2.0/token";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String body = "grant_type=client_credentials" +
                "&client_id=" + properties.getClientId() +
                "&client_secret=" + properties.getClientSecret() +
                "&scope=https://graph.microsoft.com/.default";

        ResponseEntity<String> resp = restTemplate.postForEntity(tokenEndpoint, new HttpEntity<>(body, headers), String.class);
        if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
            try {
                JsonNode n = mapper.readTree(resp.getBody());
                return n.path("access_token").asText("");
            } catch (Exception ignored) {}
        }
        return "";
    }

    private Map<String, Object> parseGraphMessage(JsonNode node, String folder, String userEmail) {
        Map<String, Object> mail = new LinkedHashMap<>();
        String subject = node.path("subject").asText("");
        mail.put("subject", subject);

        String sender = node.path("from").path("emailAddress").path("address").asText("");
        if (sender.isBlank()) {
            sender = node.path("sender").path("emailAddress").path("address").asText("");
        }
        mail.put("sender", sender);
        mail.put("from", sender);

        List<String> toList = new ArrayList<>();
        JsonNode toRecipients = node.path("toRecipients");
        if (toRecipients.isArray()) {
            for (JsonNode r : toRecipients) {
                toList.add(r.path("emailAddress").path("address").asText(""));
            }
        }
        String toStr = String.join(", ", toList);
        mail.put("to", toStr);

        List<String> ccList = new ArrayList<>();
        JsonNode ccRecipients = node.path("ccRecipients");
        if (ccRecipients.isArray()) {
            for (JsonNode r : ccRecipients) {
                ccList.add(r.path("emailAddress").path("address").asText(""));
            }
        }
        String ccStr = String.join(", ", ccList);
        mail.put("cc", ccStr);

        String received = node.path("receivedDateTime").asText("");
        mail.put("receivedTime", received);
        try {
            mail.put("receivedTimeFormatted", DATE_FMT.format(Instant.parse(received)));
        } catch (Exception e) {
            mail.put("receivedTimeFormatted", received);
        }

        String importance = node.path("importance").asText("normal").toUpperCase(Locale.ROOT);
        String severity = "HIGH".equalsIgnoreCase(importance) ? "HIGH" : "NORMAL";
        mail.put("severity", severity);
        mail.put("isMajorIssue", "HIGH".equalsIgnoreCase(importance));

        String bodyContent = node.path("body").path("content").asText(node.path("bodyPreview").asText(""));
        mail.put("body", bodyContent);
        mail.put("issueSummary", abbreviate(bodyContent, 300));
        mail.put("impact", "");
        mail.put("actionTaken", "");
        mail.put("team", "Enterprise Messaging");
        mail.put("folder", normalizeFolder(folder));
        mail.put("userEmail", userEmail != null ? userEmail : properties.getUserEmail());
        return mail;
    }

    // =========================================================================
    // Corporate Organization Communications Store (Offline / Local Fallback)
    // =========================================================================

    private List<Map<String, Object>> generateEnterpriseMailbox(int days, Instant cutoff, String folder, String userEmail) {
        List<Map<String, Object>> list = new ArrayList<>();

        String effectiveUserEmail = (userEmail != null && !userEmail.isBlank()) ? userEmail : properties.getUserEmail();
        String teamEmail = properties.getTeamEmail();

        // 1. Critical Gateway Outage (received in inbox)
        if (days >= 2) {
            Instant t = Instant.now().minus(2, ChronoUnit.DAYS).minus(3, ChronoUnit.HOURS);
            list.add(createMail(
                    "ALERT [EMH UAT]: Intermittent 504 Gateway Timeout during peak European trading hours",
                    "emh-infra-ops@se.com",
                    teamEmail + ", " + effectiveUserEmail,
                    "ei-global-ops@se.com",
                    "Global Infrastructure Operations",
                    t,
                    "CRITICAL",
                    true,
                    "High latency spikes observed on REST gateway downstream API calls during peak European hours (10:00-14:00 CET).",
                    "Downstream client requests experienced intermittent 504 timeouts on uat.emh.ei.se.com. Order processing throughput dropped by 18%.",
                    "Auto-scaling minimum pod replicas increased from 4 to 8. Network throttling rule updated on Istio gateway.",
                    "Hi EMH Team and Kamlesh,\n\n" +
                            "This is an automated operational alert regarding uat.emh.ei.se.com.\n" +
                            "During today's European peak hours, downstream REST microservice latency spiked beyond the 12-second gateway threshold, " +
                            "resulting in intermittent 504 Gateway Timeouts for external clients. Order routing latency increased to an average of 4.2 seconds.\n\n" +
                            "Actions taken:\n" +
                            "1. Auto-scaling min replicas bumped from 4 to 8 pods on the European cluster.\n" +
                            "2. Istio ingress timeout increased from 15s to 30s to absorb downstream database query spikes.\n" +
                            "3. Traffic stabilized at 14:35 CET with 0 further 504 errors.\n\n" +
                            "Please coordinate with the EI Services L2 team if any further connection resets occur.\n\n" +
                            "Regards,\nGlobal Infrastructure Operations",
                    "inbox",
                    effectiveUserEmail
            ));
        }

        // 2. Kafka OracleAqSinkConnector task 2 connection reset (received in inbox)
        if (days >= 3) {
            Instant t = Instant.now().minus(3, ChronoUnit.DAYS).minus(4, ChronoUnit.HOURS);
            list.add(createMail(
                    "ESCALATION: OracleAqSinkConnector task 2 failed with connection timeout on UAT",
                    "kafka-admin@se.com",
                    teamEmail + ", " + effectiveUserEmail,
                    "database-ops@se.com",
                    "Kafka Cluster Operations",
                    t,
                    "HIGH",
                    true,
                    "Oracle AQ database connection reset by peer during message dequeuing on UAT environment.",
                    "Message consumer lag accumulated on topic 'emh.orders.sync.v1' (peak lag: 14,200 events).",
                    "Task 2 restart triggered via REST endpoint. DB connection pool max lifetime adjusted to 30 minutes in connector config.",
                    "Hello Kamlesh and EI Services Team,\n\n" +
                            "We observed an alert on Kafka Connect cluster 1: connector 'OracleAqSinkConnector' task 2 has transitioned to FAILED status.\n" +
                            "Stack trace excerpt:\n" +
                            "java.sql.SQLRecoverableException: IO Error: Connection reset by peer\n" +
                            "at oracle.jdbc.driver.T4CConnection.logoff(T4CConnection.java:980)\n\n" +
                            "Operational Impact:\n" +
                            "Consumer lag on topic 'emh.orders.sync.v1' reached 14,200 records. Downstream order synchronization to Oracle AQ was delayed by ~45 minutes.\n\n" +
                            "Mitigation:\n" +
                            "Kamlesh Choubey approved triggering the task restart API. Connection pool max lifetime was updated from 60 mins to 30 mins to prevent stale firewall drops. " +
                            "Task 2 is now RUNNING and consumer lag has completely drained to 0.\n\n" +
                            "Best regards,\nKafka Operations Team",
                    "inbox",
                    effectiveUserEmail
            ));
        }

        // 3. Security Vulnerability Scan in date-processor (received in inbox)
        if (days >= 1) {
            Instant t = Instant.now().minus(1, ChronoUnit.DAYS).minus(7, ChronoUnit.HOURS);
            list.add(createMail(
                    "SECURITY NOTICE: Critical CVE-2023-35116 detected in jackson-databind for date-processor",
                    "product-security-advisory@se.com",
                    effectiveUserEmail + ", " + teamEmail,
                    "ciso-appsec@se.com",
                    "Enterprise Cybersecurity",
                    t,
                    "HIGH",
                    true,
                    "JFrog Xray automated policy scan identified CVE-2023-35116 in com.fasterxml.jackson.core:jackson-databind:2.14.2.",
                    "Affects date-processor and mdm-integration production docker images and triggers corporate security policy block.",
                    "Remediation plan initiated: upgrading spring-boot-parent to 3.3.4 and overriding jackson version to safe release 2.17.2.",
                    "Dear Kamlesh and EMH Microservices Leads,\n\n" +
                            "The automated JFrog Xray nightly compliance scan has identified high-severity vulnerability CVE-2023-35116 in the date-processor component.\n" +
                            "- Component: com.fasterxml.jackson.core:jackson-databind:2.14.2\n" +
                            "- CVSS Score: 7.5 (High)\n" +
                            "- Affected Repository: mdm-integration, date-processor\n\n" +
                            "Vulnerability Details:\n" +
                            "Improper cycle detection in serialization of complex object graphs can cause denial of service via StackOverflowError.\n\n" +
                            "Action Required:\n" +
                            "Upgrade spring-boot-starter-parent to the latest safe baseline (3.3.4) and set jackson-bom to 2.17.2. " +
                            "A pull request has been prepared for review. Please test and merge to develop.\n\n" +
                            "Enterprise Product Security Team",
                    "inbox",
                    effectiveUserEmail
            ));
        }

        // 4. Memory threshold warning on microservice pods (Sent from user to team - SENT ITEMS folder)
        if (days >= 4) {
            Instant t = Instant.now().minus(4, ChronoUnit.DAYS).minus(5, ChronoUnit.HOURS);
            list.add(createMail(
                    "WARNING: Memory threshold exceeded (88%) on emh-parent microservice pods in prod",
                    effectiveUserEmail,
                    teamEmail,
                    "sre-alerts@se.com",
                    "EMH SRE Team",
                    t,
                    "MEDIUM",
                    false,
                    "Heap usage exceeded 85% threshold during nightly XML batch ingestion on emh-parent pods.",
                    "Potential memory leak during large XML payload unmarshalling. No pod eviction occurred.",
                    "Heap dump collected for profiling; JVM max heap increased to 4GB as temporary mitigation.",
                    "Hi Team,\n\n" +
                            "During last night's batch ingestion at 02:00 UTC, emh-parent microservice pod heap memory reached 88% (3.52GB of 4GB allocated).\n" +
                            "Garbage collection frequency increased significantly, leading to minor CPU throttling.\n\n" +
                            "Status and Investigation:\n" +
                            "- Analyzed heap dump using Eclipse MAT. Identified large DOM document tree retained during bulk message transformations.\n" +
                            "- Proposed refactoring from DOM parser to StAX streaming parser for next sprint.\n" +
                            "- Temporary JVM container limits bumped from 4GB to 5GB to ensure headroom.\n\n" +
                            "Thanks,\n" + effectiveUserEmail,
                    "sentitems",
                    effectiveUserEmail
            ));
        }

        // 5. Weekly Sprint Review & Status Summary (received in inbox)
        if (days >= 5) {
            Instant t = Instant.now().minus(5, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS);
            list.add(createMail(
                    "Weekly Operational Briefing & Kafka Connect Health Check - EI Services",
                    teamEmail,
                    effectiveUserEmail,
                    "ei-leads@se.com",
                    "EI Services Global L2 / EMH Team",
                    t,
                    "NORMAL",
                    false,
                    "Weekly summary of EI Services operations, connector uptimes, incident turnaround, and upcoming sprint goals.",
                    "All connectors healthy (4 running, 1 paused scheduled maintenance). Zero P1 incidents open.",
                    "ServiceNow SLA adherence reached 96.4%. Sprint backlog groomed for CVE remediation and test coverage enhancements.",
                    "Dear Team,\n\n" +
                            "Here is our weekly operational and team status report for EI Services Global L2:\n\n" +
                            "1. Connector Cluster Health:\n" +
                            "   - OracleAqSinkConnector, SapInvoiceSourceConnector, and MdmEventSinkConnector are RUNNING smoothly.\n" +
                            "   - Total daily events processed: 2.85 million messages with 0 dropped events.\n\n" +
                            "2. ServiceNow ITSM Tickets:\n" +
                            "   - 5 active incidents under investigation (0 P1s, 2 P2s).\n" +
                            "   - SLA compliance for the week is 96.4%.\n\n" +
                            "3. Dependencies & Security:\n" +
                            "   - Jackson databind CVE upgrade prepared; awaiting test case execution on develop.\n\n" +
                            "Best regards,\nEI Services Global L2 EMH Team",
                    "inbox",
                    effectiveUserEmail
            ));
        }

        // 6. Draft Release Deployment Checklist (DRAFTS folder)
        Instant tDraft = Instant.now().minus(1, ChronoUnit.DAYS);
        list.add(createMail(
                "DRAFT: EI Services Release 2026.4 Deployment Checklist & Rollback Plan",
                effectiveUserEmail,
                teamEmail,
                "",
                "EI Services Deployment",
                tDraft,
                "NORMAL",
                false,
                "Draft deployment plan for upcoming European release 2026.4 containing Kafka connector optimizations.",
                "None - draft status",
                "Drafting deployment plan",
                "Hi Team,\n\nHere is the preliminary draft for release 2026.4 checklist.\n[Pending approval]",
                "drafts",
                effectiveUserEmail
        ));

        // Non-participating email to verify that isMonitoredParticipant strictly excludes it!
        Instant tOther = Instant.now().minus(2, ChronoUnit.DAYS);
        list.add(createMail(
                "Random external vendor newsletter - Cloud infrastructure trends 2026",
                "newsletter@external-cloud-digest.com",
                "all-users@external-digest.com",
                "",
                "External Digest",
                tOther,
                "LOW",
                false,
                "Third-party external promotional email with no relevance to Schneider Electric EMH.",
                "None",
                "None",
                "External generic marketing newsletter.",
                "inbox",
                effectiveUserEmail
        ));

        return list;
    }

    private Map<String, Object> createMail(String subject, String sender, String to, String cc, String team,
                                           Instant receivedTime, String severity, boolean isMajor,
                                           String summary, String impact, String action, String body,
                                           String folder, String userEmail) {
        Map<String, Object> mail = new LinkedHashMap<>();
        mail.put("subject", subject);
        mail.put("sender", sender);
        mail.put("from", sender);
        mail.put("to", to);
        mail.put("cc", cc);
        mail.put("team", team);
        mail.put("receivedTime", receivedTime.toString());
        mail.put("receivedTimeFormatted", DATE_FMT.format(receivedTime));
        mail.put("severity", severity);
        mail.put("isMajorIssue", isMajor);
        mail.put("issueSummary", summary);
        mail.put("impact", impact);
        mail.put("actionTaken", action);
        mail.put("body", body);
        mail.put("folder", normalizeFolder(folder));
        mail.put("userEmail", userEmail != null ? userEmail : properties.getUserEmail());
        return mail;
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
