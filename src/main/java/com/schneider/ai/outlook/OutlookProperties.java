package com.schneider.ai.outlook;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Configuration properties for Microsoft Outlook / Office 365 email intelligence.
 * All settings are configurable via application.properties or .env:
 * OUTLOOK_MONITORED_EMAILS, OUTLOOK_USER_EMAIL, OUTLOOK_TEAM_EMAIL, OUTLOOK_GRAPH_URL.
 */
@Data
@Component
@ConfigurationProperties(prefix = "outlook")
public class OutlookProperties {

    /**
     * Primary organization user email, dynamically resolved from active user session or config.
     */
    private String userEmail = "";

    /**
     * Organization team email, e.g. EIIFW.EMHTeam@se.com
     */
    private String teamEmail = "EIIFW.EMHTeam@se.com";

    /**
     * Comma-separated list of monitored corporate emails.
     */
    private String monitoredEmails = "EIIFW.EMHTeam@se.com";

    /**
     * Microsoft Graph API base URL
     */
    private String graphUrl = "https://graph.microsoft.com/v1.0";

    /**
     * Azure AD / Entra ID client ID for Graph API
     */
    private String clientId = "";

    /**
     * Azure AD / Entra ID client secret for Graph API
     */
    private String clientSecret = "";

    /**
     * Azure AD / Entra ID tenant ID
     */
    private String tenantId = "";

    /**
     * Cache TTL in seconds for scanned email summaries
     */
    private int cacheTtlSeconds = 300;

    /**
     * Whether to trust all SSL certificates
     */
    private boolean trustAll = true;

    /**
     * Returns a list of monitored emails in lowercase for case-insensitive matching.
     */
    public List<String> getMonitoredEmailsList() {
        return getMonitoredEmailsList(null);
    }

    public List<String> getMonitoredEmailsList(String dynamicUserEmail) {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        if (dynamicUserEmail != null && !dynamicUserEmail.isBlank()) {
            set.add(dynamicUserEmail.trim().toLowerCase(Locale.ROOT));
        } else if (userEmail != null && !userEmail.isBlank()) {
            set.add(userEmail.trim().toLowerCase(Locale.ROOT));
        }
        if (teamEmail != null && !teamEmail.isBlank()) {
            set.add(teamEmail.trim().toLowerCase(Locale.ROOT));
        }
        if (monitoredEmails != null && !monitoredEmails.isBlank()) {
            Arrays.stream(monitoredEmails.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .forEach(set::add);
        }
        return new java.util.ArrayList<>(set);
    }

    /**
     * Determines whether the email involves at least one of the monitored organization email addresses.
     */
    public boolean isMonitoredParticipant(String from, String to, String cc) {
        return isMonitoredParticipant(from, to, cc, null);
    }

    public boolean isMonitoredParticipant(String from, String to, String cc, String dynamicUserEmail) {
        List<String> monitored = getMonitoredEmailsList(dynamicUserEmail);
        String fromNorm = from != null ? from.trim().toLowerCase(Locale.ROOT) : "";
        String toNorm = to != null ? to.trim().toLowerCase(Locale.ROOT) : "";
        String ccNorm = cc != null ? cc.trim().toLowerCase(Locale.ROOT) : "";

        for (String target : monitored) {
            if (fromNorm.contains(target) || toNorm.contains(target) || ccNorm.contains(target)) {
                return true;
            }
        }
        return false;
    }
}
