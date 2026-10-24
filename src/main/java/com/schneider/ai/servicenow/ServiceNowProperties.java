package com.schneider.ai.servicenow;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration properties for ServiceNow incident and service request integration.
 * All settings can be overridden via application.properties or .env:
 * SERVICENOW_URL, SERVICENOW_ASSIGNMENT_GROUP, SERVICENOW_ACTIVE, SERVICENOW_STATES, SERVICENOW_ASSIGNED_TO, etc.
 */
@Data
@Component
@ConfigurationProperties(prefix = "servicenow")
public class ServiceNowProperties {

    /**
     * ServiceNow base URL, e.g. https://schneider.service-now.com
     */
    private String baseUrl = "https://schneider.service-now.com";

    /**
     * Whether only active tickets should be included. Default: true.
     */
    private boolean active = true;

    /**
     * Assignment group name for incident and task filtering, e.g. "EI Services Global L2"
     */
    private String assignmentGroup = "EI Services Global L2";

    /**
     * Comma-separated list of allowed states, e.g. "New,Assigned,In Progress,On Hold"
     */
    private String states = "New,Assigned,In Progress,On Hold";

    /**
     * Assigned to user name for filtering incidents and service tasks, e.g. "ARUN KUMAR"
     */
    private String assignedTo = "ARUN KUMAR";

    /**
     * ServiceNow API username
     */
    private String username = "";

    /**
     * ServiceNow API password
     */
    private String password = "";

    /**
     * ServiceNow API Bearer token or PAT
     */
    private String token = "";

    /**
     * Number of days after which an open ticket is considered aging. Default: 5.
     */
    private int agingDays = 5;

    /**
     * Cache TTL in seconds for dashboard summary. Default: 300.
     */
    private long cacheTtlSeconds = 300;

    /**
     * Whether to trust all TLS certificates (e.g. corporate proxy/inspection). Default: true.
     */
    private boolean trustAll = true;

    public List<String> getStatesList() {
        if (states == null || states.isBlank()) {
            return List.of("New", "Assigned", "In Progress", "On Hold");
        }
        return Arrays.stream(states.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
