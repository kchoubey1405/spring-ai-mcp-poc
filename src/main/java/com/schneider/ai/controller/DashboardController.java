package com.schneider.ai.controller;

import com.schneider.ai.jfrog.VulnerabilityService;
import com.schneider.ai.servicenow.ServiceNowService;
import com.schneider.ai.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API for the context panel of the UI. Every section answers from a short cache and reports
 * status READY / ERROR (never invented numbers); add ?refresh=true to reload from the source system.
 * The vulnerability endpoints live in VulnerabilityController.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final VulnerabilityService vulnerabilityService;
    private final ServiceNowService serviceNowService;

    /** All sections in one call. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getDashboardData(
            @RequestParam(defaultValue = "false") boolean refresh,
            @RequestParam(name = "sessionId", required = false) String sessionId) {
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("jira", dashboardService.getJiraSummary(refresh, sessionId));
        dashboard.put("pullRequests", dashboardService.getOpenPullRequests(refresh, sessionId));
        dashboard.put("kafka", dashboardService.getKafkaSummary(refresh));
        dashboard.put("vulnerabilities", vulnerabilityService.summary());
        dashboard.put("servicenow", serviceNowService.getSummary(refresh, sessionId));
        return ResponseEntity.ok(dashboard);
    }

    /** ServiceNow active incidents and service requests summary with optional dynamic filter. */
    @GetMapping("/servicenow")
    public ResponseEntity<Map<String, Object>> getServiceNow(
            @RequestParam(defaultValue = "false") boolean refresh,
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "assignmentGroup", required = false) String assignmentGroup,
            @RequestParam(name = "states", required = false) String states,
            @RequestParam(name = "assignedTo", required = false) String assignedTo) {

        if (active != null || assignmentGroup != null || states != null || assignedTo != null) {
            ServiceNowService.FilterCriteria base = serviceNowService.getFilterCriteria();
            ServiceNowService.FilterCriteria custom = new ServiceNowService.FilterCriteria(
                    active != null ? active : base.isActive(),
                    assignmentGroup != null && !assignmentGroup.isBlank() ? assignmentGroup.trim() : base.getAssignmentGroup(),
                    states != null && !states.isBlank()
                            ? java.util.Arrays.stream(states.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
                            : base.getStates(),
                    assignedTo != null ? assignedTo.trim() : base.getAssignedTo()
            );
            return ResponseEntity.ok(serviceNowService.getSummaryWithFilter(refresh, sessionId, custom));
        }
        return ResponseEntity.ok(serviceNowService.getSummary(refresh, sessionId));
    }

    /** Get current ServiceNow active filter criteria. */
    @GetMapping("/servicenow/filter")
    public ResponseEntity<ServiceNowService.FilterCriteria> getServiceNowFilter() {
        return ResponseEntity.ok(serviceNowService.getFilterCriteria());
    }

    /** Update current ServiceNow active filter criteria dynamically from UI or API. */
    @PostMapping("/servicenow/filter")
    public ResponseEntity<Map<String, Object>> updateServiceNowFilter(
            @RequestBody ServiceNowService.FilterCriteria newFilter,
            @RequestParam(name = "sessionId", required = false) String sessionId) {
        ServiceNowService.FilterCriteria updated = serviceNowService.updateFilterCriteria(newFilter);
        Map<String, Object> summary = serviceNowService.getSummary(true, sessionId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filterCriteria", updated);
        response.put("summary", summary);
        return ResponseEntity.ok(response);
    }

    /** EMH Jira tickets counted per status. */
    @GetMapping("/jira")
    public ResponseEntity<Map<String, Object>> getJira(
            @RequestParam(defaultValue = "false") boolean refresh,
            @RequestParam(name = "sessionId", required = false) String sessionId) {
        return ResponseEntity.ok(dashboardService.getJiraSummary(refresh, sessionId));
    }

    /** Open pull requests of every repository of the organisation, project-wise, with the total. */
    @GetMapping("/prs")
    public ResponseEntity<Map<String, Object>> getOpenPRs(
            @RequestParam(defaultValue = "false") boolean refresh,
            @RequestParam(name = "sessionId", required = false) String sessionId) {
        return ResponseEntity.ok(dashboardService.getOpenPullRequests(refresh, sessionId));
    }

    /** Kafka Connect connectors: total and running / paused / failed. */
    @GetMapping("/kafka")
    public ResponseEntity<Map<String, Object>> getKafka(@RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(dashboardService.getKafkaSummary(refresh));
    }

    /** Restart a specific failed task of a Kafka connector: POST /connectors/{connector}/tasks/{taskId}/restart */
    @PostMapping(value = {"/kafka/connectors/{connector}/tasks/{taskId}/restart", "/connectors/{connector}/tasks/{taskId}/restart"})
    public ResponseEntity<Map<String, Object>> restartKafkaTask(
            @PathVariable("connector") String connector,
            @PathVariable("taskId") int taskId) {
        return ResponseEntity.ok(dashboardService.restartKafkaTask(connector, taskId));
    }

    /** Restart the entire Kafka connector */
    @PostMapping(value = {"/kafka/connectors/{connector}/restart", "/connectors/{connector}/restart"})
    public ResponseEntity<Map<String, Object>> restartKafkaConnector(
            @PathVariable("connector") String connector) {
        return ResponseEntity.ok(dashboardService.restartKafkaConnector(connector));
    }

    /** Restart all failed tasks of a Kafka connector (or all connectors if connector is empty) */
    @PostMapping(value = {"/kafka/connectors/{connector}/restart-failed", "/connectors/{connector}/restart-failed"})
    public ResponseEntity<Map<String, Object>> restartFailedTasks(
            @PathVariable("connector") String connector) {
        return ResponseEntity.ok(dashboardService.restartFailedTasks(connector));
    }

    /** Restart all failed tasks across all Kafka connectors */
    @PostMapping(value = {"/kafka/restart-failed", "/restart-failed"})
    public ResponseEntity<Map<String, Object>> restartAllFailedTasks() {
        return ResponseEntity.ok(dashboardService.restartFailedTasks(null));
    }

    /** Query-param fallback restart endpoint: POST /kafka/restart?connector=...&taskId=... */
    @PostMapping(value = {"/kafka/restart", "/restart"})
    public ResponseEntity<Map<String, Object>> restartKafka(
            @RequestParam("connector") String connector,
            @RequestParam(value = "taskId", required = false) Integer taskId) {
        if (taskId != null) {
            return ResponseEntity.ok(dashboardService.restartKafkaTask(connector, taskId));
        }
        return ResponseEntity.ok(dashboardService.restartFailedTasks(connector));
    }
}
