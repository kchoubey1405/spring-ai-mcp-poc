package com.schneider.ai.servicenow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schneider.ai.service.TlsTrust;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for ServiceNow integration:
 * - Monitors Incidents for assignment group "EI Services Global L2"
 * - Provides status-wise active incident counts (strictly excluding Closed, Resolved, Cancelled)
 * - Highlights Priority 1 (P1 Critical) and Priority 2 (P2 High) counts
 * - Highlights SLA breached incidents and aging incidents (> agingDays old)
 * - Monitors Service Requests (sc_req_item / RITM) with aging and SLA breach highlights
 * - Executes search, details lookup, and incident updates (state, work notes, comments, priority)
 *
 * If corporate credentials or network are active, connects to the ServiceNow Table REST API.
 * Otherwise, seamlessly falls back to a persistent, manageable in-memory store preloaded with
 * Schneider Electric EI Services Global L2 operations data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceNowService {

    private final ServiceNowProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.schneider.ai.auth.SessionAuthService sessionAuthService;

    private RestTemplate restTemplate;

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class FilterCriteria {
        private boolean active = true;
        private String assignmentGroup = "EI Services Global L2";
        private List<String> states = new ArrayList<>(List.of("New", "Assigned", "In Progress", "On Hold"));
        private String assignedTo = "ARUN KUMAR";
    }

    private final FilterCriteria currentFilter = new FilterCriteria();

    // Cache management
    private volatile Map<String, Object> cachedSummary;
    private volatile Instant lastSummaryFetch;
    private final Object summaryLock = new Object();

    // Standard in-memory fallback store for offline/demo operation
    private final Map<String, Map<String, Object>> mockIncidents = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> mockServiceRequests = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        this.restTemplate = createRestTemplate(properties.isTrustAll());
        this.currentFilter.setActive(properties.isActive());
        this.currentFilter.setAssignmentGroup(properties.getAssignmentGroup());
        this.currentFilter.setStates(new ArrayList<>(properties.getStatesList()));
        this.currentFilter.setAssignedTo(properties.getAssignedTo());
        initMockData();
    }

    public FilterCriteria getFilterCriteria() {
        return new FilterCriteria(
                currentFilter.isActive(),
                currentFilter.getAssignmentGroup(),
                new ArrayList<>(currentFilter.getStates()),
                currentFilter.getAssignedTo()
        );
    }

    public FilterCriteria updateFilterCriteria(FilterCriteria newFilter) {
        if (newFilter != null) {
            this.currentFilter.setActive(newFilter.isActive());
            if (newFilter.getAssignmentGroup() != null && !newFilter.getAssignmentGroup().isBlank()) {
                this.currentFilter.setAssignmentGroup(newFilter.getAssignmentGroup().trim());
            }
            if (newFilter.getStates() != null && !newFilter.getStates().isEmpty()) {
                this.currentFilter.setStates(new ArrayList<>(newFilter.getStates()));
            }
            if (newFilter.getAssignedTo() != null) {
                this.currentFilter.setAssignedTo(newFilter.getAssignedTo().trim());
            }
            synchronized (summaryLock) {
                cachedSummary = null;
            }
            log.info("Updated ServiceNow active filter criteria: {}", buildFilterDescription(currentFilter));
        }
        return getFilterCriteria();
    }

    private RestTemplate createRestTemplate(boolean trustAll) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                if (connection instanceof HttpsURLConnection https) {
                    try {
                        SSLContext sslContext = trustAll ? TlsTrust.insecureContext() : SSLContext.getDefault();
                        https.setSSLSocketFactory(sslContext.getSocketFactory());
                        if (trustAll) {
                            https.setHostnameVerifier((hostname, session) -> true);
                        }
                    } catch (Exception e) {
                        log.warn("Could not configure TLS for ServiceNow: {}", e.getMessage());
                    }
                }
            }
        };
        factory.setConnectTimeout(8_000);
        factory.setReadTimeout(20_000);
        return new RestTemplate(factory);
    }

    /**
     * Get incident and service request dashboard summary using default configured filter.
     */
    public Map<String, Object> getSummary(boolean forceRefresh) {
        return getSummary(forceRefresh, null);
    }

    public Map<String, Object> getSummary(boolean forceRefresh, String sessionId) {
        return getSummaryWithFilter(forceRefresh, sessionId, currentFilter);
    }

    public Map<String, Object> getSummaryWithFilter(boolean forceRefresh, String sessionId, FilterCriteria filter) {
        FilterCriteria effectiveFilter = filter != null ? filter : currentFilter;
        boolean isCurrentFilter = effectiveFilter == currentFilter ||
                (effectiveFilter.isActive() == currentFilter.isActive()
                        && Objects.equals(effectiveFilter.getAssignmentGroup(), currentFilter.getAssignmentGroup())
                        && Objects.equals(effectiveFilter.getAssignedTo(), currentFilter.getAssignedTo())
                        && Objects.equals(effectiveFilter.getStates(), currentFilter.getStates()));

        if (!forceRefresh && isCurrentFilter && cachedSummary != null && isSummaryFresh()) {
            return cachedSummary;
        }

        synchronized (summaryLock) {
            if (!forceRefresh && isCurrentFilter && cachedSummary != null && isSummaryFresh()) {
                return cachedSummary;
            }

            Map<String, Object> summary;
            try {
                if (isLiveConfigured(sessionId)) {
                    summary = fetchLiveSummary(sessionId, effectiveFilter);
                } else {
                    summary = buildMockSummary(effectiveFilter);
                }
            } catch (Exception ex) {
                log.warn("Failed to fetch live ServiceNow summary ({}). Falling back to Schneider Electric local store.", ex.getMessage());
                summary = buildMockSummary(effectiveFilter);
                summary.put("liveError", ex.getMessage());
            }

            if (isCurrentFilter) {
                cachedSummary = summary;
                lastSummaryFetch = Instant.now();
            }
            return summary;
        }
    }

    /**
     * Search active incidents for assignment group.
     */
    public Map<String, Object> searchIncidents(String query, String state, Integer priority, Integer limit) {
        int max = (limit == null || limit <= 0) ? 25 : Math.min(limit, 100);
        if (isLiveConfigured()) {
            try {
                return searchLiveIncidents(query, state, priority, max);
            } catch (Exception ex) {
                log.warn("Live ServiceNow search failed: {}. Falling back to in-memory store.", ex.getMessage());
            }
        }
        return searchMockIncidents(query, state, priority, max);
    }

    /**
     * Get full details of a specific incident or service task/request.
     */
    public Map<String, Object> getIncidentDetails(String incidentNumber) {
        if (incidentNumber == null || incidentNumber.isBlank()) {
            return Map.of("status", "ERROR", "message", "Incident or Service Task number is required");
        }
        String normalized = incidentNumber.trim().toUpperCase(Locale.ROOT);

        if (isLiveConfigured()) {
            try {
                Map<String, Object> liveDetails = fetchLiveIncidentDetails(normalized);
                if (liveDetails != null) {
                    return liveDetails;
                }
            } catch (Exception ex) {
                log.warn("Live ServiceNow lookup failed: {}. Falling back to store.", ex.getMessage());
            }
        }

        Map<String, Object> incident = mockIncidents.get(normalized);
        if (incident != null) {
            Map<String, Object> res = new LinkedHashMap<>(incident);
            res.put("status", "READY");
            res.put("ticketType", "Incident");
            return res;
        }

        Map<String, Object> serviceReq = mockServiceRequests.get(normalized);
        if (serviceReq != null) {
            Map<String, Object> res = new LinkedHashMap<>(serviceReq);
            res.put("status", "READY");
            res.put("ticketType", "Service Task");
            return res;
        }

        return Map.of("status", "ERROR", "message", "Ticket " + normalized + " (Incident or Service Task) not found");
    }

    /**
     * Get active service requests (sc_req_item).
     */
    public Map<String, Object> getServiceRequests(String state, Integer limit) {
        int max = (limit == null || limit <= 0) ? 25 : Math.min(limit, 100);
        if (isLiveConfigured()) {
            try {
                return fetchLiveServiceRequests(state, max);
            } catch (Exception ex) {
                log.warn("Live ServiceNow service requests failed: {}. Falling back to local store.", ex.getMessage());
            }
        }
        return getMockServiceRequests(state, max);
    }

    /**
     * Update an incident or service task/request (work notes, customer comments, state, priority).
     */
    public Map<String, Object> updateIncident(String incidentNumber, String state, String workNotes, String comments, Integer priority) {
        if (incidentNumber == null || incidentNumber.isBlank()) {
            return Map.of("status", "ERROR", "message", "Incident or Service Task number is required");
        }
        String normalized = incidentNumber.trim().toUpperCase(Locale.ROOT);

        Map<String, Object> updatePayload = new LinkedHashMap<>();
        if (state != null && !state.isBlank()) updatePayload.put("state", state);
        if (workNotes != null && !workNotes.isBlank()) updatePayload.put("work_notes", workNotes);
        if (comments != null && !comments.isBlank()) updatePayload.put("comments", comments);
        if (priority != null && priority > 0) updatePayload.put("priority", priority);

        if (updatePayload.isEmpty()) {
            return Map.of("status", "ERROR", "message", "No fields to update provided (provide state, workNotes, comments, or priority)");
        }

        boolean liveUpdated = false;
        if (isLiveConfigured()) {
            try {
                liveUpdated = updateLiveIncident(normalized, updatePayload);
            } catch (Exception ex) {
                log.warn("Failed to update live ticket {}: {}. Updating local store.", normalized, ex.getMessage());
            }
        }

        boolean isServiceReq = mockServiceRequests.containsKey(normalized) || normalized.startsWith("SCTASK") || normalized.startsWith("RITM");
        Map<String, Map<String, Object>> targetStore = isServiceReq ? mockServiceRequests : mockIncidents;

        // Always update local store so state is reflected
        Map<String, Object> local = targetStore.computeIfAbsent(normalized, k -> new LinkedHashMap<>());
        local.put("number", normalized);
        if (state != null && !state.isBlank()) {
            local.put("state", state);
            if (isServiceReq) {
                local.put("stage", state);
            }
        }
        if (priority != null && priority > 0) {
            local.put("priorityNumber", priority);
            local.put("priority", priority == 1 ? "P1 - Critical" : priority == 2 ? "P2 - High" : priority == 3 ? "P3 - Moderate" : "P" + priority);
        }

        @SuppressWarnings("unchecked")
        List<String> notesList = (List<String>) local.computeIfAbsent("workNotesHistory", k -> new ArrayList<>());
        if (workNotes != null && !workNotes.isBlank()) {
            notesList.add(Instant.now() + " [EMH AI Assistant]: " + workNotes);
            local.put("work_notes", workNotes);
        }

        @SuppressWarnings("unchecked")
        List<String> commentsList = (List<String>) local.computeIfAbsent("commentsHistory", k -> new ArrayList<>());
        if (comments != null && !comments.isBlank()) {
            commentsList.add(Instant.now() + " [EMH AI Assistant]: " + comments);
            local.put("comments", comments);
        }
        local.put("sys_updated_on", Instant.now().toString());

        // Invalidate summary cache so UI gets fresh data
        synchronized (summaryLock) {
            cachedSummary = null;
        }

        String ticketType = isServiceReq ? "Service Task" : "Incident";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("ticketNumber", normalized);
        result.put("incidentNumber", normalized);
        result.put("ticketType", ticketType);
        result.put("updatedFields", updatePayload);
        result.put("liveSynced", liveUpdated);
        result.put("currentRecord", local);
        result.put("message", ticketType + " " + normalized + " updated successfully." + (liveUpdated ? " (Synced with ServiceNow)" : ""));
        return result;
    }

    // =========================================================================
    // Live ServiceNow Table API Integration
    // =========================================================================

    private String resolveServiceNowToken(String sessionId) {
        if (sessionAuthService != null && sessionId != null && !sessionId.isBlank()) {
            java.util.Optional<String> token = sessionAuthService.getToken(sessionId, "servicenow");
            if (token.isPresent() && !token.get().isBlank()) {
                return token.get();
            }
        }
        if (sessionAuthService != null) {
            String fb = sessionAuthService.getFallbackTokenForService("servicenow");
            if (fb != null && !fb.isBlank()) {
                return fb;
            }
        }
        return properties.getToken() != null && !properties.getToken().isBlank() ? properties.getToken() : "";
    }

    private boolean isLiveConfigured() {
        return isLiveConfigured(null);
    }

    private boolean isLiveConfigured(String sessionId) {
        String token = resolveServiceNowToken(sessionId);
        return (token != null && !token.isBlank())
                || (properties.getUsername() != null && !properties.getUsername().isBlank()
                && properties.getPassword() != null && !properties.getPassword().isBlank())
                || (properties.getToken() != null && !properties.getToken().isBlank());
    }

    private HttpHeaders serviceNowHeaders() {
        return serviceNowHeaders(null);
    }

    private HttpHeaders serviceNowHeaders(String sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);

        String token = resolveServiceNowToken(sessionId);
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token.trim());
        } else if (properties.getToken() != null && !properties.getToken().isBlank()) {
            headers.setBearerAuth(properties.getToken().trim());
        } else if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            String auth = properties.getUsername().trim() + ":" + properties.getPassword().trim();
            headers.setBasicAuth(Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)));
        }
        return headers;
    }

    private Map<String, Object> fetchLiveSummary() {
        return fetchLiveSummary(null, currentFilter);
    }

    private Map<String, Object> fetchLiveSummary(String sessionId, FilterCriteria filter) {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        String group = filter.getAssignmentGroup();

        // Incidents query: active=true ^ assignment_group = group ^ state NOT IN (6: Resolved, 7: Closed, 8: Canceled)
        StringBuilder incQuery = new StringBuilder();
        if (filter.isActive()) {
            incQuery.append("active=true^");
        }
        if (group != null && !group.isBlank()) {
            incQuery.append("assignment_group.name=").append(group).append("^");
        }
        incQuery.append("stateNOT IN6,7,8^");
        if (filter.getAssignedTo() != null && !filter.getAssignedTo().isBlank()) {
            incQuery.append("assigned_to.nameLIKE").append(filter.getAssignedTo()).append("^");
        }
        incQuery.append("ORDERBYDESCsys_updated_on");

        URI incidentUri = UriComponentsBuilder.fromUriString(base + "/api/now/table/incident")
                .queryParam("sysparm_query", incQuery.toString())
                .queryParam("sysparm_fields", "number,sys_id,short_description,state,priority,assignment_group,assigned_to,sys_created_on,sys_updated_on,made_sla,has_breached,active")
                .queryParam("sysparm_display_value", "true")
                .queryParam("sysparm_limit", "100")
                .build().toUri();

        // Service Requests query
        StringBuilder reqQuery = new StringBuilder();
        if (filter.isActive()) {
            reqQuery.append("active=true^");
        }
        if (group != null && !group.isBlank()) {
            reqQuery.append("assignment_group.name=").append(group).append("^");
        }
        reqQuery.append("stateNOT IN3,4,7^");
        if (filter.getAssignedTo() != null && !filter.getAssignedTo().isBlank()) {
            reqQuery.append("assigned_to.nameLIKE").append(filter.getAssignedTo()).append("^");
        }
        reqQuery.append("ORDERBYDESCsys_updated_on");

        URI reqUri = UriComponentsBuilder.fromUriString(base + "/api/now/table/sc_req_item")
                .queryParam("sysparm_query", reqQuery.toString())
                .queryParam("sysparm_fields", "number,sys_id,short_description,state,priority,assignment_group,assigned_to,sys_created_on,sys_updated_on,made_sla,has_breached,stage,active")
                .queryParam("sysparm_display_value", "true")
                .queryParam("sysparm_limit", "100")
                .build().toUri();

        HttpHeaders headers = serviceNowHeaders(sessionId);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        List<Map<String, Object>> incidentList = new ArrayList<>();
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        int p1Count = 0;
        int p2Count = 0;
        int slaBreachedCount = 0;
        int agingCount = 0;

        ResponseEntity<String> incResp = restTemplate.exchange(incidentUri, HttpMethod.GET, entity, String.class);
        if (incResp.getStatusCode().is2xxSuccessful() && incResp.getBody() != null) {
            try {
                JsonNode root = mapper.readTree(incResp.getBody());
                JsonNode results = root.path("result");
                if (results.isArray()) {
                    for (JsonNode node : results) {
                        Map<String, Object> inc = parseIncidentNode(node);
                        if (!matchesFilter(inc, filter)) continue;

                        incidentList.add(inc);
                        String state = (String) inc.get("state");
                        statusCounts.put(state, statusCounts.getOrDefault(state, 0) + 1);

                        int priority = (int) inc.getOrDefault("priorityNumber", 3);
                        if (priority == 1) p1Count++;
                        if (priority == 2) p2Count++;

                        if (Boolean.TRUE.equals(inc.get("slaBreached"))) slaBreachedCount++;
                        if (Boolean.TRUE.equals(inc.get("isAging"))) agingCount++;
                    }
                }
            } catch (Exception e) {
                log.warn("Error parsing ServiceNow incident response: {}", e.getMessage());
            }
        }

        // Service Requests
        List<Map<String, Object>> reqList = new ArrayList<>();
        int reqAgingCount = 0;
        int reqSlaBreachedCount = 0;

        try {
            ResponseEntity<String> reqResp = restTemplate.exchange(reqUri, HttpMethod.GET, entity, String.class);
            if (reqResp.getStatusCode().is2xxSuccessful() && reqResp.getBody() != null) {
                JsonNode root = mapper.readTree(reqResp.getBody());
                JsonNode results = root.path("result");
                if (results.isArray()) {
                    for (JsonNode node : results) {
                        Map<String, Object> req = parseRequestNode(node);
                        if (!matchesFilter(req, filter)) continue;

                        reqList.add(req);
                        if (Boolean.TRUE.equals(req.get("slaBreached"))) reqSlaBreachedCount++;
                        if (Boolean.TRUE.equals(req.get("isAging"))) reqAgingCount++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error fetching service requests: {}", e.getMessage());
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "READY");
        summary.put("assignmentGroup", filter.getAssignmentGroup());
        summary.put("assignedTo", filter.getAssignedTo());
        summary.put("filterCriteria", Map.of(
                "active", filter.isActive(),
                "assignmentGroup", filter.getAssignmentGroup(),
                "states", filter.getStates(),
                "assignedTo", filter.getAssignedTo()
        ));
        summary.put("filterDescription", buildFilterDescription(filter));
        summary.put("url", properties.getBaseUrl());
        summary.put("totalIncidents", incidentList.size());
        summary.put("statusCounts", statusCounts);
        summary.put("p1Count", p1Count);
        summary.put("p2Count", p2Count);
        summary.put("slaBreachedCount", slaBreachedCount);
        summary.put("agingCount", agingCount);
        summary.put("incidents", incidentList);

        // Service Requests summary
        summary.put("serviceRequestsTotal", reqList.size());
        summary.put("serviceRequestsAgingCount", reqAgingCount);
        summary.put("serviceRequestsSlaBreachedCount", reqSlaBreachedCount);
        summary.put("serviceRequests", reqList);

        summary.put("fetchedAt", Instant.now().toString());
        summary.put("isLive", true);
        return summary;
    }

    private Map<String, Object> parseIncidentNode(JsonNode node) {
        Map<String, Object> inc = new LinkedHashMap<>();
        String number = node.path("number").asText("");
        inc.put("number", number);
        inc.put("sysId", node.path("sys_id").asText(""));
        inc.put("shortDescription", node.path("short_description").asText(""));
        inc.put("active", !"false".equalsIgnoreCase(node.path("active").asText("true")));

        String stateStr = node.path("state").asText("New");
        inc.put("state", normalizeIncidentState(stateStr));

        String priStr = node.path("priority").asText("3");
        int priNum = parsePriorityNumber(priStr);
        inc.put("priorityNumber", priNum);
        inc.put("priority", priNum == 1 ? "P1 - Critical" : priNum == 2 ? "P2 - High" : priNum == 3 ? "P3 - Moderate" : "P" + priNum);

        inc.put("assignedTo", node.path("assigned_to").asText("Unassigned"));
        inc.put("assignmentGroup", node.path("assignment_group").asText(properties.getAssignmentGroup()));

        String createdOn = node.path("sys_created_on").asText("");
        inc.put("sysCreatedOn", createdOn);
        inc.put("sysUpdatedOn", node.path("sys_updated_on").asText(""));

        // SLA breached check
        boolean madeSla = !"false".equalsIgnoreCase(node.path("made_sla").asText("true"));
        boolean hasBreached = "true".equalsIgnoreCase(node.path("has_breached").asText("false"));
        boolean slaBreached = !madeSla || hasBreached;
        inc.put("slaBreached", slaBreached);

        // Aging check
        boolean aging = calculateIsAging(createdOn, properties.getAgingDays());
        inc.put("isAging", aging);

        return inc;
    }

    private Map<String, Object> parseRequestNode(JsonNode node) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("number", node.path("number").asText(""));
        req.put("sysId", node.path("sys_id").asText(""));
        req.put("shortDescription", node.path("short_description").asText(""));
        req.put("active", !"false".equalsIgnoreCase(node.path("active").asText("true")));
        req.put("state", node.path("state").asText("Open"));
        req.put("stage", node.path("stage").asText(""));
        req.put("priority", node.path("priority").asText("3 - Moderate"));
        req.put("assignedTo", node.path("assigned_to").asText("Unassigned"));

        String createdOn = node.path("sys_created_on").asText("");
        req.put("sysCreatedOn", createdOn);

        boolean madeSla = !"false".equalsIgnoreCase(node.path("made_sla").asText("true"));
        boolean hasBreached = "true".equalsIgnoreCase(node.path("has_breached").asText("false"));
        req.put("slaBreached", !madeSla || hasBreached);
        req.put("isAging", calculateIsAging(createdOn, properties.getAgingDays()));
        return req;
    }

    private Map<String, Object> searchLiveIncidents(String query, String state, Integer priority, int limit) {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        StringBuilder qb = new StringBuilder("assignment_group.name=").append(properties.getAssignmentGroup());
        qb.append("^stateNOT IN6,7,8");

        if (state != null && !state.isBlank()) {
            qb.append("^state=").append(state);
        }
        if (priority != null && priority > 0) {
            qb.append("^priority=").append(priority);
        }
        if (query != null && !query.isBlank()) {
            qb.append("^123TEXTQUERY321=").append(query);
        }
        qb.append("^ORDERBYDESCsys_updated_on");

        URI uri = UriComponentsBuilder.fromUriString(base + "/api/now/table/incident")
                .queryParam("sysparm_query", qb.toString())
                .queryParam("sysparm_fields", "number,sys_id,short_description,state,priority,assigned_to,sys_created_on,sys_updated_on,made_sla,has_breached")
                .queryParam("sysparm_display_value", "true")
                .queryParam("sysparm_limit", String.valueOf(limit))
                .build().toUri();

        ResponseEntity<String> resp = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(serviceNowHeaders()), String.class);
        List<Map<String, Object>> resultsList = new ArrayList<>();
        if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
            try {
                JsonNode root = mapper.readTree(resp.getBody());
                JsonNode results = root.path("result");
                if (results.isArray()) {
                    for (JsonNode n : results) {
                        resultsList.add(parseIncidentNode(n));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed parsing search results: {}", e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "READY");
        out.put("total", resultsList.size());
        out.put("incidents", resultsList);
        return out;
    }

    private Map<String, Object> fetchLiveIncidentDetails(String incidentNumber) {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        boolean isTaskOrReq = incidentNumber.startsWith("SCTASK") || incidentNumber.startsWith("RITM") || incidentNumber.startsWith("REQ");
        String table = incidentNumber.startsWith("SCTASK") ? "sc_task" : isTaskOrReq ? "sc_req_item" : "incident";

        URI uri = UriComponentsBuilder.fromUriString(base + "/api/now/table/" + table)
                .queryParam("sysparm_query", "number=" + incidentNumber)
                .queryParam("sysparm_fields", "number,sys_id,short_description,description,state,stage,priority,urgency,severity,assignment_group,assigned_to,sys_created_on,sys_updated_on,made_sla,has_breached,comments_and_work_notes,work_notes,comments")
                .queryParam("sysparm_display_value", "true")
                .queryParam("sysparm_limit", "1")
                .build().toUri();

        ResponseEntity<String> resp = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(serviceNowHeaders()), String.class);
        if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
            try {
                JsonNode root = mapper.readTree(resp.getBody());
                JsonNode results = root.path("result");
                if (results.isArray() && results.size() > 0) {
                    JsonNode node = results.get(0);
                    Map<String, Object> details = isTaskOrReq ? parseRequestNode(node) : parseIncidentNode(node);
                    details.put("description", node.path("description").asText(""));
                    details.put("urgency", node.path("urgency").asText(""));
                    details.put("severity", node.path("severity").asText(""));
                    details.put("commentsAndWorkNotes", node.path("comments_and_work_notes").asText(""));
                    details.put("ticketType", isTaskOrReq ? "Service Task" : "Incident");
                    details.put("status", "READY");
                    return details;
                }
            } catch (Exception e) {
                log.warn("Failed parsing ticket details for {}: {}", incidentNumber, e.getMessage());
            }
        }
        return null;
    }

    private Map<String, Object> fetchLiveServiceRequests(String state, int limit) {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        StringBuilder qb = new StringBuilder("assignment_group.name=").append(properties.getAssignmentGroup());
        qb.append("^stateNOT IN3,4,7");
        if (state != null && !state.isBlank()) {
            qb.append("^state=").append(state);
        }
        qb.append("^ORDERBYDESCsys_updated_on");

        URI uri = UriComponentsBuilder.fromUriString(base + "/api/now/table/sc_req_item")
                .queryParam("sysparm_query", qb.toString())
                .queryParam("sysparm_fields", "number,sys_id,short_description,state,priority,assignment_group,assigned_to,sys_created_on,sys_updated_on,made_sla,has_breached,stage")
                .queryParam("sysparm_display_value", "true")
                .queryParam("sysparm_limit", String.valueOf(limit))
                .build().toUri();

        ResponseEntity<String> resp = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(serviceNowHeaders()), String.class);
        List<Map<String, Object>> list = new ArrayList<>();
        if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
            try {
                JsonNode root = mapper.readTree(resp.getBody());
                JsonNode results = root.path("result");
                if (results.isArray()) {
                    for (JsonNode n : results) {
                        list.add(parseRequestNode(n));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed parsing service requests: {}", e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "READY");
        out.put("total", list.size());
        out.put("serviceRequests", list);
        return out;
    }

    private boolean updateLiveIncident(String incidentNumber, Map<String, Object> updatePayload) {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        boolean isTaskOrReq = incidentNumber.startsWith("SCTASK") || incidentNumber.startsWith("RITM") || incidentNumber.startsWith("REQ");
        String table = incidentNumber.startsWith("SCTASK") ? "sc_task" : isTaskOrReq ? "sc_req_item" : "incident";

        Map<String, Object> details = fetchLiveIncidentDetails(incidentNumber);
        if (details == null || !details.containsKey("sysId")) {
            return false;
        }
        String sysId = (String) details.get("sysId");
        URI uri = UriComponentsBuilder.fromUriString(base + "/api/now/table/" + table + "/" + sysId).build().toUri();

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(updatePayload, serviceNowHeaders());
        ResponseEntity<String> resp = restTemplate.exchange(uri, HttpMethod.PATCH, entity, String.class);
        return resp.getStatusCode().is2xxSuccessful();
    }

    // =========================================================================
    // Mock / Offline / Standard Demonstration Store
    // =========================================================================

    private void initMockData() {
        mockIncidents.clear();
        mockServiceRequests.clear();

        // High quality standard data for "EI Services Global L2" assigned to "ARUN KUMAR" (Active = True)
        addMockIncident("INC0982341", "Kafka OracleAqSinkConnector task 2 failed with connection timeout on UAT",
                "In Progress", 2, "ARUN KUMAR", Instant.now().minus(6, ChronoUnit.DAYS), true, true,
                "Oracle AQ database connection reset by peer during message dequeuing on uat.emh.ei.se.com.");

        addMockIncident("INC0983110", "Memory threshold alert on emh-parent microservice pods in prod",
                "Assigned", 2, "ARUN KUMAR", Instant.now().minus(2, ChronoUnit.DAYS), false, true,
                "Heap usage peaked at 88% after recent batch ingestion. GC analysis requested.");

        addMockIncident("INC0984520", "High CVE vulnerability reported in jackson-databind for date-processor",
                "New", 3, "ARUN KUMAR", Instant.now().minus(1, ChronoUnit.DAYS), false, true,
                "JFrog Xray detected CVE-2023-35116 in com.fasterxml.jackson.core:jackson-databind:2.14.2.");

        addMockIncident("INC0981204", "Third-party dependency scan sync failure in libs-release-prod-virtual",
                "On Hold", 4, "ARUN KUMAR", Instant.now().minus(9, ChronoUnit.DAYS), true, true,
                "Waiting on Artifactory proxy mirror update from central security repo.");

        addMockIncident("INC0985230", "EMH Gateway intermittent 504 Gateway Timeout during peak traffic",
                "In Progress", 1, "ARUN KUMAR", Instant.now().minus(3, ChronoUnit.DAYS), false, true,
                "High latency spikes observed on REST gateway downstream API calls during European hours.");

        // Additional incidents (other assignees or closed) to verify filter exclusion
        addMockIncident("INC0984901", "Certificate expiration warning for internal mTLS endpoint uat.emh.ei.se.com",
                "New", 3, "Kamlesh Choubey", Instant.now().minus(7, ChronoUnit.DAYS), true, true,
                "Certificate expires within 14 days. SAN list requires review for new DNS entries.");

        addMockIncident("INC0980100", "Database connection pool exhausted on staging",
                "Resolved", 3, "ARUN KUMAR", Instant.now().minus(12, ChronoUnit.DAYS), false, false,
                "Staging pool resized and connection leak closed in datasource config.");

        // Service Tasks / Requests assigned to ARUN KUMAR (Active = True, Group = EI Services Global L2)
        addMockServiceRequest("SCTASK0041205", "Request for Artifactory write access to sd-ei-emh-eu-docker-prod-fed",
                "In Progress", "Fulfillment", "ARUN KUMAR", Instant.now().minus(6, ChronoUnit.DAYS), true, true);

        addMockServiceRequest("SCTASK0042450", "SonarQube quality gate bypass review for spring-ai-mcp-poc develop branch",
                "Assigned", "Peer Review", "ARUN KUMAR", Instant.now().minus(1, ChronoUnit.DAYS), false, true);

        addMockServiceRequest("SCTASK0043102", "SSL Client certificate renewal for EI Services Global L2 gateway",
                "On Hold", "Pending Approval", "ARUN KUMAR", Instant.now().minus(7, ChronoUnit.DAYS), true, true);

        addMockServiceRequest("SCTASK0043511", "Kafka topic ACL permissions provisioning for payment-events-v1",
                "New", "Awaiting Assignment", "ARUN KUMAR", Instant.now().minus(2, ChronoUnit.DAYS), false, true);

        // Service Tasks for other teams / inactive to verify filter exclusion
        addMockServiceRequest("SCTASK0042319", "Firewall rule request for Kafka Connect cluster port 8083 communication",
                "Open", "Network Provisioning", "Security Ops", Instant.now().minus(2, ChronoUnit.DAYS), false, true);

        addMockServiceRequest("SCTASK0040101", "Developer laptop VPN profile renewal",
                "Closed Complete", "Completed", "ARUN KUMAR", Instant.now().minus(15, ChronoUnit.DAYS), false, false);
    }

    private void addMockIncident(String number, String shortDesc, String state, int priority, String assignedTo, Instant created, boolean slaBreached, String description) {
        addMockIncident(number, shortDesc, state, priority, assignedTo, created, slaBreached, true, description);
    }

    private void addMockIncident(String number, String shortDesc, String state, int priority, String assignedTo, Instant created, boolean slaBreached, boolean active, String description) {
        Map<String, Object> inc = new LinkedHashMap<>();
        inc.put("number", number);
        inc.put("sysId", UUID.nameUUIDFromBytes(number.getBytes(StandardCharsets.UTF_8)).toString());
        inc.put("shortDescription", shortDesc);
        inc.put("description", description);
        inc.put("state", state);
        inc.put("active", active);
        inc.put("priorityNumber", priority);
        inc.put("priority", priority == 1 ? "P1 - Critical" : priority == 2 ? "P2 - High" : priority == 3 ? "P3 - Moderate" : "P" + priority);
        inc.put("assignedTo", assignedTo);
        inc.put("assignmentGroup", properties.getAssignmentGroup());
        inc.put("sysCreatedOn", created.toString());
        inc.put("sysUpdatedOn", Instant.now().toString());
        inc.put("slaBreached", slaBreached);
        inc.put("isAging", calculateIsAging(created.toString(), properties.getAgingDays()));
        inc.put("workNotesHistory", new ArrayList<>(List.of(created + " - Initial triage completed. Assigned to " + properties.getAssignmentGroup())));
        inc.put("commentsHistory", new ArrayList<>());
        mockIncidents.put(number, inc);
    }

    private void addMockServiceRequest(String number, String shortDesc, String state, String stage, String assignedTo, Instant created, boolean slaBreached) {
        addMockServiceRequest(number, shortDesc, state, stage, assignedTo, created, slaBreached, true, shortDesc);
    }

    private void addMockServiceRequest(String number, String shortDesc, String state, String stage, String assignedTo, Instant created, boolean slaBreached, boolean active) {
        addMockServiceRequest(number, shortDesc, state, stage, assignedTo, created, slaBreached, active, shortDesc);
    }

    private void addMockServiceRequest(String number, String shortDesc, String state, String stage, String assignedTo, Instant created, boolean slaBreached, boolean active, String description) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("number", number);
        req.put("sysId", UUID.nameUUIDFromBytes(number.getBytes(StandardCharsets.UTF_8)).toString());
        req.put("shortDescription", shortDesc);
        req.put("description", description != null ? description : shortDesc);
        req.put("state", state);
        req.put("active", active);
        req.put("stage", stage);
        req.put("priorityNumber", 3);
        req.put("priority", "P3 - Moderate");
        req.put("assignedTo", assignedTo);
        req.put("assignmentGroup", properties.getAssignmentGroup());
        req.put("sysCreatedOn", created.toString());
        req.put("sysUpdatedOn", Instant.now().toString());
        req.put("slaBreached", slaBreached);
        req.put("isAging", calculateIsAging(created.toString(), properties.getAgingDays()));
        req.put("workNotesHistory", new ArrayList<>(List.of(created + " - Request submitted and assigned to " + properties.getAssignmentGroup())));
        req.put("commentsHistory", new ArrayList<>());
        mockServiceRequests.put(number, req);
    }

    private Map<String, Object> buildMockSummary() {
        return buildMockSummary(currentFilter);
    }

    private Map<String, Object> buildMockSummary(FilterCriteria filter) {
        List<Map<String, Object>> incidentList = new ArrayList<>();
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        int p1Count = 0;
        int p2Count = 0;
        int slaBreachedCount = 0;
        int agingCount = 0;

        for (Map<String, Object> inc : mockIncidents.values()) {
            if (!matchesFilter(inc, filter)) {
                continue;
            }

            incidentList.add(inc);
            String state = (String) inc.get("state");
            statusCounts.put(state, statusCounts.getOrDefault(state, 0) + 1);

            int pNum = (int) inc.getOrDefault("priorityNumber", 3);
            if (pNum == 1) p1Count++;
            if (pNum == 2) p2Count++;

            if (Boolean.TRUE.equals(inc.get("slaBreached"))) slaBreachedCount++;
            if (Boolean.TRUE.equals(inc.get("isAging"))) agingCount++;
        }

        // Sort incidents by Priority (P1 first, then P2) then by created date desc
        incidentList.sort((a, b) -> {
            int pComp = Integer.compare((int) a.getOrDefault("priorityNumber", 3), (int) b.getOrDefault("priorityNumber", 3));
            if (pComp != 0) return pComp;
            return String.valueOf(b.get("number")).compareTo(String.valueOf(a.get("number")));
        });

        List<Map<String, Object>> reqList = new ArrayList<>();
        int reqAgingCount = 0;
        int reqSlaBreachedCount = 0;

        for (Map<String, Object> req : mockServiceRequests.values()) {
            if (!matchesFilter(req, filter)) {
                continue;
            }

            reqList.add(req);
            if (Boolean.TRUE.equals(req.get("slaBreached"))) reqSlaBreachedCount++;
            if (Boolean.TRUE.equals(req.get("isAging"))) reqAgingCount++;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "READY");
        summary.put("assignmentGroup", filter.getAssignmentGroup());
        summary.put("assignedTo", filter.getAssignedTo());
        summary.put("filterCriteria", Map.of(
                "active", filter.isActive(),
                "assignmentGroup", filter.getAssignmentGroup(),
                "states", filter.getStates(),
                "assignedTo", filter.getAssignedTo()
        ));
        summary.put("filterDescription", buildFilterDescription(filter));
        summary.put("url", properties.getBaseUrl());
        summary.put("totalIncidents", incidentList.size());
        summary.put("statusCounts", statusCounts);
        summary.put("p1Count", p1Count);
        summary.put("p2Count", p2Count);
        summary.put("slaBreachedCount", slaBreachedCount);
        summary.put("agingCount", agingCount);
        summary.put("incidents", incidentList);

        // Service Requests
        summary.put("serviceRequestsTotal", reqList.size());
        summary.put("serviceRequestsAgingCount", reqAgingCount);
        summary.put("serviceRequestsSlaBreachedCount", reqSlaBreachedCount);
        summary.put("serviceRequests", reqList);

        summary.put("fetchedAt", Instant.now().toString());
        summary.put("isLive", false);
        return summary;
    }

    public static boolean matchesFilter(Map<String, Object> item, FilterCriteria filter) {
        if (item == null || filter == null) return false;

        // 1. Active check
        if (filter.isActive()) {
            Object activeObj = item.get("active");
            if (activeObj != null && !Boolean.TRUE.equals(activeObj) && !"true".equalsIgnoreCase(String.valueOf(activeObj))) {
                return false;
            }
            String state = String.valueOf(item.get("state"));
            if (isExcludedState(state) || isExcludedRequestState(state)) {
                return false;
            }
        }

        // 2. Assignment Group check
        if (filter.getAssignmentGroup() != null && !filter.getAssignmentGroup().isBlank()) {
            String group = String.valueOf(item.get("assignmentGroup")).trim();
            if (!group.equalsIgnoreCase(filter.getAssignmentGroup().trim())) {
                return false;
            }
        }

        // 3. State IN (New, Assigned, In Progress, On Hold) check
        if (filter.getStates() != null && !filter.getStates().isEmpty()) {
            String state = String.valueOf(item.get("state")).trim();
            boolean stateMatched = false;
            for (String allowed : filter.getStates()) {
                if (isStateEquivalent(state, allowed)) {
                    stateMatched = true;
                    break;
                }
            }
            if (!stateMatched) {
                return false;
            }
        }

        // 4. Assigned To check
        if (filter.getAssignedTo() != null && !filter.getAssignedTo().isBlank()) {
            String assigned = String.valueOf(item.get("assignedTo")).trim();
            String target = filter.getAssignedTo().trim();
            if (!assigned.equalsIgnoreCase(target) && !assigned.toLowerCase(Locale.ROOT).contains(target.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }

        return true;
    }

    public static boolean isStateEquivalent(String actual, String target) {
        if (actual == null || target == null) return false;
        String a = actual.trim().toLowerCase(Locale.ROOT);
        String t = target.trim().toLowerCase(Locale.ROOT);

        if (a.equals(t)) return true;

        if (t.contains("new") && (a.equals("new") || a.equals("1") || a.equals("open"))) return true;
        if (t.contains("assigned") && (a.equals("assigned") || a.equals("open") || a.equals("2"))) return true;
        if ((t.contains("progress") || t.contains("work")) && (a.contains("progress") || a.equals("2") || a.contains("work"))) return true;
        if ((t.contains("hold") || t.contains("pending") || t.contains("await")) && (a.contains("hold") || a.contains("pending") || a.contains("await") || a.equals("3"))) return true;

        return false;
    }

    public static String buildFilterDescription(FilterCriteria filter) {
        if (filter == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Active = ").append(filter.isActive() ? "True" : "False");
        if (filter.getAssignmentGroup() != null && !filter.getAssignmentGroup().isBlank()) {
            sb.append(" AND Assignment Group = ").append(filter.getAssignmentGroup());
        }
        if (filter.getStates() != null && !filter.getStates().isEmpty()) {
            sb.append(" AND State IN (").append(String.join(", ", filter.getStates())).append(")");
        }
        if (filter.getAssignedTo() != null && !filter.getAssignedTo().isBlank()) {
            sb.append(" AND Assigned To ").append(filter.getAssignedTo());
        }
        return sb.toString();
    }

    private Map<String, Object> searchMockIncidents(String query, String state, Integer priority, int limit) {
        String q = query != null ? query.toLowerCase(Locale.ROOT) : "";
        List<Map<String, Object>> matched = new ArrayList<>();

        for (Map<String, Object> inc : mockIncidents.values()) {
            String incState = (String) inc.get("state");
            if (isExcludedState(incState)) continue;

            if (state != null && !state.isBlank() && !incState.equalsIgnoreCase(state)) {
                continue;
            }
            if (priority != null && priority > 0) {
                int pNum = (int) inc.getOrDefault("priorityNumber", 3);
                if (pNum != priority) continue;
            }
            if (!q.isBlank()) {
                String num = String.valueOf(inc.get("number")).toLowerCase(Locale.ROOT);
                String desc = String.valueOf(inc.get("shortDescription")).toLowerCase(Locale.ROOT);
                String full = String.valueOf(inc.get("description")).toLowerCase(Locale.ROOT);
                if (!num.contains(q) && !desc.contains(q) && !full.contains(q)) {
                    continue;
                }
            }
            matched.add(inc);
            if (matched.size() >= limit) break;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "READY");
        out.put("total", matched.size());
        out.put("incidents", matched);
        return out;
    }

    private Map<String, Object> getMockServiceRequests(String state, int limit) {
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> req : mockServiceRequests.values()) {
            String reqState = (String) req.get("state");
            if (isExcludedRequestState(reqState)) continue;

            if (state != null && !state.isBlank() && !reqState.equalsIgnoreCase(state)) {
                continue;
            }
            matched.add(req);
            if (matched.size() >= limit) break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "READY");
        out.put("total", matched.size());
        out.put("serviceRequests", matched);
        return out;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean isSummaryFresh() {
        if (lastSummaryFetch == null) return false;
        long ttlMs = Math.max(5, properties.getCacheTtlSeconds()) * 1000L;
        return (System.currentTimeMillis() - lastSummaryFetch.toEpochMilli()) < ttlMs;
    }

    /**
     * Checks if incident state is Resolved (6), Closed (7), or Cancelled (8).
     */
    public static boolean isExcludedState(String state) {
        if (state == null) return false;
        String s = state.trim().toLowerCase(Locale.ROOT);
        return s.equals("closed") || s.equals("resolved") || s.equals("cancelled")
                || s.equals("canceled") || s.equals("6") || s.equals("7") || s.equals("8");
    }

    /**
     * Checks if service request state is Closed Complete (3), Closed Incomplete (4), or Closed Skipped (7).
     */
    public static boolean isExcludedRequestState(String state) {
        if (state == null) return false;
        String s = state.trim().toLowerCase(Locale.ROOT);
        return s.contains("closed") || s.equals("cancelled") || s.equals("canceled")
                || s.equals("3") || s.equals("4") || s.equals("7");
    }

    public static boolean calculateIsAging(String createdOnIso, int agingDays) {
        if (createdOnIso == null || createdOnIso.isBlank()) return false;
        try {
            Instant created = Instant.parse(createdOnIso);
            return ChronoUnit.DAYS.between(created, Instant.now()) >= agingDays;
        } catch (DateTimeParseException ex) {
            // Check format like "2026-10-15 12:00:00"
            try {
                Instant created = Instant.parse(createdOnIso.replace(" ", "T") + "Z");
                return ChronoUnit.DAYS.between(created, Instant.now()) >= agingDays;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private static String normalizeIncidentState(String raw) {
        if (raw == null) return "New";
        String s = raw.trim();
        return switch (s) {
            case "1" -> "New";
            case "2" -> "In Progress";
            case "3" -> "On Hold";
            case "4" -> "Awaiting Caller";
            case "5" -> "Awaiting Change";
            case "6" -> "Resolved";
            case "7" -> "Closed";
            case "8" -> "Canceled";
            default -> s;
        };
    }

    private static int parsePriorityNumber(String pri) {
        if (pri == null || pri.isBlank()) return 3;
        String digits = pri.replaceAll("\\D", "");
        if (!digits.isEmpty()) {
            try {
                return Integer.parseInt(digits.substring(0, 1));
            } catch (Exception ignored) {}
        }
        String lower = pri.toLowerCase(Locale.ROOT);
        if (lower.contains("crit") || lower.contains("p1")) return 1;
        if (lower.contains("high") || lower.contains("p2")) return 2;
        if (lower.contains("low") || lower.contains("p4")) return 4;
        return 3;
    }
}
