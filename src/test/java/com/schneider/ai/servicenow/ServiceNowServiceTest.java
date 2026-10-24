package com.schneider.ai.servicenow;

import com.schneider.ai.service.AiOrchestrationService;
import com.schneider.ai.tools.ServiceNowTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServiceNowServiceTest {

    private ServiceNowProperties properties;
    private ServiceNowService serviceNowService;
    private ServiceNowTools serviceNowTools;

    @BeforeEach
    void setUp() {
        properties = new ServiceNowProperties();
        properties.setBaseUrl("https://schneider.service-now.com");
        properties.setAssignmentGroup("EI Services Global L2");
        properties.setAgingDays(5);
        properties.setActive(true);
        properties.setStates("New,Assigned,In Progress,On Hold");
        properties.setAssignedTo("ARUN KUMAR");

        serviceNowService = new ServiceNowService(properties);
        serviceNowService.init();

        serviceNowTools = new ServiceNowTools(serviceNowService);
    }

    @Test
    void summaryExcludesClosedResolvedAndCancelledIncidents() {
        Map<String, Object> summary = serviceNowService.getSummary(true);

        assertEquals("READY", summary.get("status"));
        assertEquals("EI Services Global L2", summary.get("assignmentGroup"));
        assertEquals("https://schneider.service-now.com", summary.get("url"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> statusCounts = (Map<String, Integer>) summary.get("statusCounts");
        assertNotNull(statusCounts);

        assertFalse(statusCounts.containsKey("Closed"), "Must NOT include Closed incidents");
        assertFalse(statusCounts.containsKey("Resolved"), "Must NOT include Resolved incidents");
        assertFalse(statusCounts.containsKey("Cancelled"), "Must NOT include Cancelled incidents");
        assertFalse(statusCounts.containsKey("Canceled"), "Must NOT include Canceled incidents");

        int totalIncidents = (int) summary.get("totalIncidents");
        assertTrue(totalIncidents > 0);

        int p1Count = (int) summary.get("p1Count");
        int p2Count = (int) summary.get("p2Count");
        assertTrue(p1Count >= 0);
        assertTrue(p2Count >= 0);

        int slaBreachedCount = (int) summary.get("slaBreachedCount");
        int agingCount = (int) summary.get("agingCount");
        assertTrue(slaBreachedCount >= 0);
        assertTrue(agingCount >= 0);

        // Verify service requests
        int reqTotal = (int) summary.get("serviceRequestsTotal");
        assertTrue(reqTotal > 0);
        assertNotNull(summary.get("serviceRequests"));
    }

    @Test
    void searchIncidentsByQueryAndPriority() {
        Map<String, Object> kafkaRes = serviceNowService.searchIncidents("Kafka", null, null, 10);
        assertEquals("READY", kafkaRes.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kafkaIncidents = (List<Map<String, Object>>) kafkaRes.get("incidents");
        assertFalse(kafkaIncidents.isEmpty());
        assertTrue(kafkaIncidents.stream().anyMatch(i -> "INC0982341".equals(i.get("number"))));

        // Priority 1 filter
        Map<String, Object> p1Res = serviceNowService.searchIncidents(null, null, 1, 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> p1List = (List<Map<String, Object>>) p1Res.get("incidents");
        assertFalse(p1List.isEmpty());
        assertEquals(1, p1List.get(0).get("priorityNumber"));
    }

    @Test
    void getIncidentDetailsReturnsComprehensiveFields() {
        Map<String, Object> details = serviceNowService.getIncidentDetails("INC0982341");
        assertEquals("READY", details.get("status"));
        assertEquals("INC0982341", details.get("number"));
        assertEquals("In Progress", details.get("state"));
        assertEquals(2, details.get("priorityNumber"));
        assertTrue(Boolean.TRUE.equals(details.get("slaBreached")));
        assertTrue(Boolean.TRUE.equals(details.get("isAging")));
        assertNotNull(details.get("description"));
    }

    @Test
    void updateIncidentAppendsWorkNotesAndReflectsInDetails() {
        String testNote = "Restarted Kafka OracleAqSinkConnector task 2 via REST endpoint. Checking consumer lag.";
        Map<String, Object> result = serviceNowService.updateIncident("INC0982341", "In Progress", testNote, null, null);

        assertEquals("SUCCESS", result.get("status"));
        assertEquals("INC0982341", result.get("incidentNumber"));

        Map<String, Object> details = serviceNowService.getIncidentDetails("INC0982341");
        @SuppressWarnings("unchecked")
        List<String> notesHistory = (List<String>) details.get("workNotesHistory");
        assertNotNull(notesHistory);
        assertTrue(notesHistory.stream().anyMatch(n -> n.contains(testNote)));
    }

    @Test
    void serviceNowToolsExposeAllOperations() {
        Map<String, Object> summary = serviceNowTools.getIncidentSummary(false);
        assertEquals("READY", summary.get("status"));

        Map<String, Object> search = serviceNowTools.searchIncidents("Memory", null, null, 5);
        assertEquals("READY", search.get("status"));

        Map<String, Object> reqs = serviceNowTools.getServiceRequests(null, 10);
        assertEquals("READY", reqs.get("status"));
        assertTrue((int) reqs.get("total") > 0);

        // Test servicenow_getIncidentDetails on a Service Task
        Map<String, Object> taskDetails = serviceNowTools.getIncidentDetails("SCTASK0041205");
        assertEquals("READY", taskDetails.get("status"));
        assertEquals("SCTASK0041205", taskDetails.get("number"));
        assertEquals("Service Task", taskDetails.get("ticketType"));
        assertNotNull(taskDetails.get("shortDescription"));

        // Test servicenow_addComment tool
        Map<String, Object> commentResult = serviceNowTools.addComment("SCTASK0041205", "Approved write permissions to Artifactory docker fed repo.", true);
        assertEquals("SUCCESS", commentResult.get("status"));

        // Test servicenow_updateStatus tool
        Map<String, Object> statusResult = serviceNowTools.updateStatus("SCTASK0041205", "Closed Complete", "Work completed successfully.");
        assertEquals("SUCCESS", statusResult.get("status"));
    }

    @Test
    void intentDetectionRecognizesServiceNowQueries() {
        assertTrue(AiOrchestrationService.isServiceNowRequest("show servicenow incidents"));
        assertTrue(AiOrchestrationService.isServiceNowRequest("what are the P1 and P2 incidents?"));
        assertTrue(AiOrchestrationService.isServiceNowRequest("check SLA breached incidents"));
        assertTrue(AiOrchestrationService.isServiceNowRequest("what is the status of INC0982341?"));
        assertTrue(AiOrchestrationService.isServiceNowRequest("any aging incidents for EI Services Global L2?"));
        assertTrue(AiOrchestrationService.isServiceNowRequest("show active service requests RITM0041205"));
        assertTrue(AiOrchestrationService.isServiceNowRequest("give me details of SCTASK0041205"));
        assertTrue(AiOrchestrationService.isServiceNowRequest("update status of SCTASK0041205 to In Progress"));
        assertTrue(AiOrchestrationService.isServiceNowRequest("add comment to service task SCTASK0041205: working on ACL"));

        assertFalse(AiOrchestrationService.isServiceNowRequest("mvn clean install"));
        assertFalse(AiOrchestrationService.isServiceNowRequest("git status"));
    }

    @Test
    void filterCriteriaMatchesExactSpecificationForArunKumar() {
        Map<String, Object> summary = serviceNowService.getSummary(true);

        assertEquals("READY", summary.get("status"));
        assertEquals("EI Services Global L2", summary.get("assignmentGroup"));
        assertEquals("ARUN KUMAR", summary.get("assignedTo"));

        // Verify filter description
        String filterDesc = (String) summary.get("filterDescription");
        assertNotNull(filterDesc);
        assertTrue(filterDesc.contains("Active = True"));
        assertTrue(filterDesc.contains("EI Services Global L2"));
        assertTrue(filterDesc.contains("ARUN KUMAR"));

        // All returned incidents must be assigned to ARUN KUMAR and have state in (New, Assigned, In Progress, On Hold)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> incidents = (List<Map<String, Object>>) summary.get("incidents");
        assertEquals(5, incidents.size(), "Should have exactly 5 incidents matching filter");
        for (Map<String, Object> inc : incidents) {
            assertEquals("ARUN KUMAR", inc.get("assignedTo"));
            String state = (String) inc.get("state");
            assertTrue(List.of("New", "Assigned", "In Progress", "On Hold").contains(state));
            assertNotEquals("Resolved", state);
            assertNotEquals("Closed", state);
        }

        // Verify service requests (tasks) strictly assigned to ARUN KUMAR
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqs = (List<Map<String, Object>>) summary.get("serviceRequests");
        assertEquals(4, reqs.size(), "Should have exactly 4 service requests matching filter");
        for (Map<String, Object> req : reqs) {
            assertEquals("ARUN KUMAR", req.get("assignedTo"));
            String state = (String) req.get("state");
            assertTrue(List.of("New", "Assigned", "In Progress", "On Hold").contains(state));
            assertNotEquals("Closed Complete", state);
        }
    }

    @Test
    void dynamicFilterUpdateWorksCorrectly() {
        // Change filter to Kamlesh Choubey
        ServiceNowService.FilterCriteria custom = new ServiceNowService.FilterCriteria(
                true,
                "EI Services Global L2",
                List.of("New", "Assigned"),
                "Kamlesh Choubey"
        );
        serviceNowService.updateFilterCriteria(custom);

        Map<String, Object> summary = serviceNowService.getSummary(true);
        assertEquals("Kamlesh Choubey", summary.get("assignedTo"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> incidents = (List<Map<String, Object>>) summary.get("incidents");
        assertEquals(1, incidents.size());
        assertEquals("INC0984901", incidents.get(0).get("number"));
        assertEquals("Kamlesh Choubey", incidents.get(0).get("assignedTo"));
    }
}
