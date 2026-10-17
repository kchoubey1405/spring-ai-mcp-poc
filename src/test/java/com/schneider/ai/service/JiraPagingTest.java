package com.schneider.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JiraPagingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void recognisesPagingRequests() {
        assertTrue(JiraPaging.isPagination("give me the next 25"));
        assertTrue(JiraPaging.isPagination("Next 25 EMH jira please"));
        assertTrue(JiraPaging.isPagination("next page"));
        assertTrue(JiraPaging.isPagination("show more tickets"));
        assertTrue(JiraPaging.isPagination("page 3"));
        assertTrue(JiraPaging.isPagination("third page"));
        assertTrue(JiraPaging.isPagination("more results"));
        assertTrue(JiraPaging.isPagination("continue"));
        assertTrue(JiraPaging.isPagination("next"));
        assertFalse(JiraPaging.isPagination("give me the list of EMH jira"));
        assertFalse(JiraPaging.isPagination("explain more details about the ticket"));
        assertFalse(JiraPaging.isPagination("continue with the fix for CMDM-8449"));
        assertFalse(JiraPaging.isPagination(null));
    }

    @Test
    void readsSizeAndPage() {
        assertEquals(25, JiraPaging.requestedSize("next 25").getAsInt());
        assertEquals(10, JiraPaging.requestedSize("give me next 10 tickets").getAsInt());
        assertEquals(100, JiraPaging.requestedSize("next 500").getAsInt());
        assertTrue(JiraPaging.requestedSize("next page").isEmpty());
        assertEquals(3, JiraPaging.requestedPage("show page 3"));
        assertEquals(2, JiraPaging.requestedPage("second page"));
        assertEquals(0, JiraPaging.requestedPage("next 25"));
    }

    @Test
    void nextStartContinuesAfterTheLastPage() {
        JiraPaging.State first = new JiraPaging.State("project = X", 0, 25);
        assertEquals(25, JiraPaging.resolveStart(first, "next 25", 25));
        JiraPaging.State second = new JiraPaging.State("project = X", 25, 25);
        assertEquals(50, JiraPaging.resolveStart(second, "next 25", 25));
        assertEquals(0, JiraPaging.resolveStart(second, "previous page", 25));
        assertEquals(50, JiraPaging.resolveStart(first, "page 3", 25));
        assertEquals(35, JiraPaging.resolveStart(new JiraPaging.State("q", 25, 10), "next 10", 10));
    }

    @Test
    void offsetInJqlBecomesStartAt() throws Exception {
        String fixed = JiraPaging.sanitizeSearchArguments(mapper,
                "{\"jql\":\"Components in (\\\"EMH\\\") AND status NOT IN (\\\"Closed\\\") OFFSET 25\"}");
        JsonNode node = mapper.readTree(fixed);
        assertFalse(node.get("jql").asText().toUpperCase().contains("OFFSET"));
        assertEquals(25, node.get("startAt").asInt());
        assertEquals(25, node.get("maxResults").asInt());
        assertTrue(node.get("jql").asText().endsWith("ORDER BY created DESC"));
    }

    @Test
    void existingArgumentsAreKept() throws Exception {
        String fixed = JiraPaging.sanitizeSearchArguments(mapper,
                "{\"jql\":\"project = X ORDER BY updated DESC\",\"startAt\":50,\"maxResults\":10}");
        JsonNode node = mapper.readTree(fixed);
        assertEquals("project = X ORDER BY updated DESC", node.get("jql").asText());
        assertEquals(50, node.get("startAt").asInt());
        assertEquals(10, node.get("maxResults").asInt());

        JiraPaging.State state = JiraPaging.stateOf(mapper, fixed);
        assertNotNull(state);
        assertEquals(50, state.startAt());
        assertEquals(10, state.pageSize());
        assertNull(JiraPaging.stateOf(mapper, "{\"startAt\":1}"));
        assertEquals("not json", JiraPaging.sanitizeSearchArguments(mapper, "not json"));
    }

    @Test
    void findsTheSearchResultInsideMcpWrappers() throws Exception {
        String inner = "{\"startAt\":25,\"maxResults\":25,\"total\":264,\"issues\":[{\"key\":\"EMH-1\",\"fields\":{\"summary\":\"A | B\","
                + "\"status\":{\"name\":\"BACKLOG\"},\"assignee\":null,\"priority\":{\"name\":\"High\"},\"updated\":\"2026-09-30T10:00:00.000+0000\"}}]}";
        String wrapped = mapper.writeValueAsString(List.of(Map.of("type", "text", "text", inner)));

        JsonNode direct = JiraPaging.findSearchResult(mapper, inner);
        JsonNode viaWrapper = JiraPaging.findSearchResult(mapper, wrapped);
        assertNotNull(direct);
        assertNotNull(viaWrapper);
        assertEquals(264, viaWrapper.get("total").asInt());
        assertNull(JiraPaging.findSearchResult(mapper, "{\"success\":false}"));
        assertNull(JiraPaging.findSearchResult(mapper, "plain text"));

        String page = JiraPaging.render(viaWrapper, 25, 25, "https://jira.se.com/");
        assertTrue(page.contains("tickets 26-26 of 264"));
        assertTrue(page.contains("[EMH-1](https://jira.se.com/browse/EMH-1)"));
        assertTrue(page.contains("A \\| B"));
        assertTrue(page.contains("Unassigned"));
        assertTrue(page.contains("2026-09-30"));
    }

    @Test
    void rendersEndOfList() throws Exception {
        JsonNode empty = mapper.readTree("{\"total\":264,\"issues\":[]}");
        assertTrue(JiraPaging.render(empty, 275, 25, "https://jira.se.com").contains("no more tickets"));

        StringBuilder issues = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            issues.append(i == 0 ? "" : ",").append("{\"key\":\"EMH-").append(i).append("\",\"fields\":{}}");
        }
        JsonNode full = mapper.readTree("{\"total\":264,\"issues\":[" + issues + "]}");
        String page = JiraPaging.render(full, 0, 25, "https://jira.se.com");
        assertTrue(page.contains("tickets 1-25 of 264"));
        assertTrue(page.contains("Say \"next 25\" for tickets 26-50."));

        JsonNode last = mapper.readTree("{\"total\":26,\"issues\":[{\"key\":\"EMH-9\",\"fields\":{}}]}");
        assertTrue(JiraPaging.render(last, 25, 25, "https://jira.se.com").contains("last page"));
    }
}
