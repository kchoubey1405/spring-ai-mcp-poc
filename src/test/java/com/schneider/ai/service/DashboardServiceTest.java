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
                json("{\"fields\":{\"status\":{\"name\":\"In Progress\"}}}"));
        Map<String, Integer> counts = DashboardService.countByStatus(issues);
        assertEquals(List.of("BACKLOG", "In Progress", "Unknown"), List.copyOf(counts.keySet()));
        assertEquals(3, counts.get("BACKLOG"));
        assertEquals(2, counts.get("In Progress"));
        assertEquals(1, counts.get("Unknown"));
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
}
