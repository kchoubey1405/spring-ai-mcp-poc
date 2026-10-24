package com.schneider.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DashboardServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    @Test
    void connectorWithOneFailedTaskIsFailedEvenIfOthersRun() throws Exception {
        assertEquals("FAILED", DashboardService.classify(json(
                "{\"connector\":{\"state\":\"RUNNING\"},\"tasks\":[{\"id\":0,\"state\":\"RUNNING\"},{\"id\":1,\"state\":\"FAILED\"},{\"id\":2,\"state\":\"RUNNING\"}]}")));
    }

    @Test
    void classifiesRunningPausedFailedAndOther() throws Exception {
        assertEquals("RUNNING", DashboardService.classify(json(
                "{\"connector\":{\"state\":\"RUNNING\"},\"tasks\":[{\"id\":0,\"state\":\"RUNNING\"}]}")));
        assertEquals("RUNNING", DashboardService.classify(json("{\"connector\":{\"state\":\"RUNNING\"},\"tasks\":[]}")));
        assertEquals("PAUSED", DashboardService.classify(json(
                "{\"connector\":{\"state\":\"PAUSED\"},\"tasks\":[{\"id\":0,\"state\":\"PAUSED\"}]}")));
        assertEquals("FAILED", DashboardService.classify(json(
                "{\"connector\":{\"state\":\"FAILED\"},\"tasks\":[]}")));
        assertEquals("OTHER", DashboardService.classify(json(
                "{\"connector\":{\"state\":\"UNASSIGNED\"},\"tasks\":[]}")));
        assertEquals("UNKNOWN", DashboardService.classify(null));
    }

    @Test
    void countsTicketsPerStatusBiggestFirst() throws Exception {
        List<JsonNode> issues = List.of(
                json("{\"fields\":{\"status\":{\"name\":\"In Progress\"}}}"),
                json("{\"fields\":{\"status\":{\"name\":\"BACKLOG\"}}}"),
                json("{\"fields\":{\"status\":{\"name\":\"BACKLOG\"}}}"),
                json("{\"fields\":{\"status\":{\"name\":\"BACKLOG\"}}}"),
                json("{\"fields\":{}}"),
                json("{\"fields\":{\"status\":{\"name\":\"Closed\"}}}"),
                json("{\"fields\":{\"status\":{\"name\":\"In Production\"}}}"),
                json("{\"fields\":{\"status\":{\"name\":\"Cancelled\"}}}"),
                json("{\"fields\":{\"status\":{\"name\":\"Done\"}}}"),
                json("{\"fields\":{\"status\":{\"name\":\"Resolved\"}}}"),
                json("{\"fields\":{\"status\":{\"name\":\"In Progress\"}}}"));
        Map<String, Integer> counts = DashboardService.countByStatus(issues);
        assertEquals(List.of("BACKLOG", "In Progress", "Unknown"), List.copyOf(counts.keySet()));
        assertEquals(3, counts.get("BACKLOG"));
        assertEquals(2, counts.get("In Progress"));
        assertEquals(1, counts.get("Unknown"));
        assertEquals(6, counts.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void repositoryNameComesFromTheApiUrl() {
        assertEquals("mdm-integration",
                DashboardService.repoOf("https://github.schneider-electric.com/api/v3/repos/ifw-microservices/mdm-integration"));
        assertEquals("x", DashboardService.repoOf("x"));
    }

    @Test
    void sectionCachesAndKeepsTheLastGoodValueOnFailure() {
        DashboardService.Section section = new DashboardService.Section("test", Duration.ofMinutes(5));
        AtomicInteger loads = new AtomicInteger();

        Map<String, Object> first = section.get(false, () -> {
            loads.incrementAndGet();
            return new LinkedHashMap<>(Map.of("total", 7));
        });
        assertEquals("READY", first.get("status"));
        assertEquals(7, first.get("total"));

        section.get(false, () -> {
            loads.incrementAndGet();
            return new LinkedHashMap<>(Map.of("total", 8));
        });
        assertEquals(1, loads.get(), "second call is served from the cache");

        Map<String, Object> afterFailure = section.get(true, () -> {
            throw new IllegalStateException("boom");
        });
        assertEquals(7, afterFailure.get("total"));
        assertEquals(true, afterFailure.get("stale"));
        assertTrue(String.valueOf(afterFailure.get("message")).contains("boom"));
    }

    @Test
    void sectionReportsAnErrorInsteadOfInventingData() {
        DashboardService.Section section = new DashboardService.Section("test", Duration.ofMinutes(5));
        Map<String, Object> result = section.get(false, () -> {
            throw new IllegalStateException("401 Unauthorized");
        });
        assertEquals("ERROR", result.get("status"));
        assertNotNull(result.get("message"));
        assertEquals(null, result.get("total"));
    }

    @Test
    void connectorRowExtractsFailedTaskIdsAndTasks() throws Exception {
        JsonNode status = json("{\"connector\":{\"state\":\"RUNNING\"},\"tasks\":[{\"id\":0,\"state\":\"RUNNING\"},{\"id\":2,\"state\":\"FAILED\"}]}");
        Map<String, Object> row = DashboardService.connectorRow("OracleAqSinkConnector", status, null);

        assertEquals("OracleAqSinkConnector", row.get("name"));
        assertEquals("FAILED", row.get("health"));
        assertEquals("RUNNING", row.get("state"));
        assertEquals(2, row.get("tasks"));

        @SuppressWarnings("unchecked")
        List<String> failedTasks = (List<String>) row.get("failedTasks");
        assertEquals(List.of("task 2"), failedTasks);

        @SuppressWarnings("unchecked")
        List<Integer> failedTaskIds = (List<Integer>) row.get("failedTaskIds");
        assertEquals(List.of(2), failedTaskIds);
    }

    @Test
    void isKafkaRequestIdentifiesRestartAndConnectorQueries() {
        assertTrue(AiOrchestrationService.isKafkaRequest("restart task 2 of OracleAqSinkConnector"));
        assertTrue(AiOrchestrationService.isKafkaRequest("curl POST https://uat.emh.ei.se.com/api/kafka-connect-1/connectors/OracleAqSinkConnector/tasks/2/restart"));
        assertTrue(AiOrchestrationService.isKafkaRequest("what kafka connectors are currently failed?"));
        assertTrue(AiOrchestrationService.isKafkaRequest("restart failed kafka connectors"));
    }
}
