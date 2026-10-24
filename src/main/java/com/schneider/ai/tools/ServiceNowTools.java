package com.schneider.ai.tools;

import com.schneider.ai.servicenow.ServiceNowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Spring AI Tools for ServiceNow operations:
 * - Incident summary: status-wise counts, P1/P2 counts, aging, and SLA breached metrics.
 * - Search active incidents for assignment group 'EI Services Global L2'.
 * - Inspect full incident record and SLA details.
 * - Monitor open Service Requests (sc_req_item / RITM) with aging & SLA highlights.
 * - Update incident (work notes, customer comments, status, priority).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceNowTools {

    private final ServiceNowService serviceNowService;

    @Tool(name = "servicenow_getIncidentSummary", description = """
            Retrieve the operational ServiceNow incident and service request summary for assignment group 'EI Services Global L2'.
            Returns status-wise counts (strictly excluding Closed, Resolved, Cancelled), Priority 1 (P1) and Priority 2 (P2) counts,
            aging incidents (>5 days), SLA breached incidents count, and open service requests (RITMs) metrics.
            """)
    public Map<String, Object> getIncidentSummary(
            @ToolParam(required = false, description = "true to force refresh from ServiceNow API") Boolean refresh) {
        log.info("Tool servicenow_getIncidentSummary invoked (refresh={})", refresh);
        return serviceNowService.getSummary(Boolean.TRUE.equals(refresh));
    }

    @Tool(name = "servicenow_searchIncidents", description = """
            Search active ServiceNow incidents assigned to 'EI Services Global L2' by query text, status, or priority.
            Excludes closed, resolved, or cancelled incidents.
            """)
    public Map<String, Object> searchIncidents(
            @ToolParam(required = false, description = "Search query keyword, error text, or incident number") String query,
            @ToolParam(required = false, description = "Filter by status, e.g. 'New', 'In Progress', 'On Hold'") String state,
            @ToolParam(required = false, description = "Filter by priority integer, e.g. 1 for P1 Critical, 2 for P2 High") Integer priority,
            @ToolParam(required = false, description = "Maximum number of incidents to return (default 25)") Integer limit) {
        log.info("Tool servicenow_searchIncidents invoked (query={}, state={}, priority={})", query, state, priority);
        return serviceNowService.searchIncidents(query, state, priority, limit);
    }

    @Tool(name = "servicenow_getIncidentDetails", description = """
            Get complete details of a specific ServiceNow incident (e.g. 'INC0982341') or service task/request (e.g. 'SCTASK0041205' or 'RITM0041205').
            Returns short description, detailed symptoms/description, state, stage, priority, assigned to, created date, SLA breached status, aging status, and comments/work notes history.
            """)
    public Map<String, Object> getIncidentDetails(
            @ToolParam(description = "Incident or Service Task number, e.g. 'INC0982341' or 'SCTASK0041205'") String incidentNumber) {
        log.info("Tool servicenow_getIncidentDetails invoked for {}", incidentNumber);
        return serviceNowService.getIncidentDetails(incidentNumber);
    }

    @Tool(name = "servicenow_getServiceRequests", description = """
            Retrieve open ServiceNow Service Requests (sc_req_item / RITMs) assigned to 'EI Services Global L2'.
            Returns active items, aging status (>5 days), and SLA breach metrics.
            """)
    public Map<String, Object> getServiceRequests(
            @ToolParam(required = false, description = "Filter by state, e.g. 'Open', 'Work in Progress', 'Pending'") String state,
            @ToolParam(required = false, description = "Maximum number of records to return (default 25)") Integer limit) {
        log.info("Tool servicenow_getServiceRequests invoked (state={}, limit={})", state, limit);
        return serviceNowService.getServiceRequests(state, limit);
    }

    @Tool(name = "servicenow_updateIncident", description = """
            Update a ServiceNow incident (INC...) or service task (SCTASK... / RITM...) for assignment group 'EI Services Global L2'.
            Allows appending internal work notes, customer comments, transitioning state (e.g. 'In Progress', 'On Hold', 'Resolved', 'Closed Complete'), or updating priority.
            """)
    public Map<String, Object> updateIncident(
            @ToolParam(description = "Incident or Service Task number to update, e.g. 'INC0982341' or 'SCTASK0041205'") String incidentNumber,
            @ToolParam(required = false, description = "New state, e.g. 'In Progress', 'On Hold', 'Resolved', 'Closed Complete'") String state,
            @ToolParam(required = false, description = "Internal work note to append") String workNotes,
            @ToolParam(required = false, description = "Customer visible comment to append") String comments,
            @ToolParam(required = false, description = "New priority number (1=Critical, 2=High, 3=Moderate)") Integer priority) {
        log.info("Tool servicenow_updateIncident invoked for {} (state={}, priority={})", incidentNumber, state, priority);
        return serviceNowService.updateIncident(incidentNumber, state, workNotes, comments, priority);
    }

    @Tool(name = "servicenow_addComment", description = """
            Add an internal work note or customer-visible comment to a ServiceNow incident (INC...) or service task (SCTASK... / RITM...).
            """)
    public Map<String, Object> addComment(
            @ToolParam(description = "Incident or Service Task number, e.g. 'INC0982341' or 'SCTASK0041205'") String ticketNumber,
            @ToolParam(description = "Comment or note message content") String comment,
            @ToolParam(required = false, description = "true for internal work note (default), false for customer-visible comment") Boolean isWorkNote) {
        boolean workNote = isWorkNote == null || Boolean.TRUE.equals(isWorkNote);
        log.info("Tool servicenow_addComment invoked for {} (workNote={})", ticketNumber, workNote);
        return serviceNowService.updateIncident(ticketNumber, null, workNote ? comment : null, !workNote ? comment : null, null);
    }

    @Tool(name = "servicenow_updateStatus", description = """
            Update the state/status of a ServiceNow incident (INC...) or service task (SCTASK... / RITM...).
            Supported states include: 'New', 'Assigned', 'In Progress', 'On Hold', 'Resolved', 'Closed Complete'.
            """)
    public Map<String, Object> updateStatus(
            @ToolParam(description = "Incident or Service Task number, e.g. 'INC0982341' or 'SCTASK0041205'") String ticketNumber,
            @ToolParam(description = "New state to apply, e.g. 'In Progress', 'On Hold', 'Resolved', 'Closed Complete'") String state,
            @ToolParam(required = false, description = "Optional work note explaining the status transition") String workNotes) {
        log.info("Tool servicenow_updateStatus invoked for {} (state={})", ticketNumber, state);
        return serviceNowService.updateIncident(ticketNumber, state, workNotes, null, null);
    }
}
