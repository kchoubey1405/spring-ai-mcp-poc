package com.schneider.ai.controller;

import com.schneider.ai.jfrog.VulnerabilityService;
import com.schneider.ai.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    /** All sections in one call. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getDashboardData(@RequestParam(defaultValue = "false") boolean refresh) {
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("jira", dashboardService.getJiraSummary(refresh));
        dashboard.put("pullRequests", dashboardService.getOpenPullRequests(refresh));
        dashboard.put("kafka", dashboardService.getKafkaSummary(refresh));
        dashboard.put("vulnerabilities", vulnerabilityService.summary());
        return ResponseEntity.ok(dashboard);
    }

    /** EMH Jira tickets counted per status. */
    @GetMapping("/jira")
    public ResponseEntity<Map<String, Object>> getJira(@RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(dashboardService.getJiraSummary(refresh));
    }

    /** Open pull requests of every repository of the organisation, project-wise, with the total. */
    @GetMapping("/prs")
    public ResponseEntity<Map<String, Object>> getOpenPRs(@RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(dashboardService.getOpenPullRequests(refresh));
    }

    /** Kafka Connect connectors: total and running / paused / failed. */
    @GetMapping("/kafka")
    public ResponseEntity<Map<String, Object>> getKafka(@RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(dashboardService.getKafkaSummary(refresh));
    }
}
