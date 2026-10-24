package com.schneider.ai.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schneider.ai.jfrog.VulnModels;
import com.schneider.ai.jfrog.VulnerabilityService;
import com.schneider.ai.outlook.OutlookProperties;
import com.schneider.ai.outlook.OutlookService;
import com.schneider.ai.service.DashboardService;
import com.schneider.ai.servicenow.ServiceNowProperties;
import com.schneider.ai.servicenow.ServiceNowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class PptReportServiceTest {

    @TempDir
    Path tempDir;

    private DashboardService dashboardService;
    private ServiceNowService serviceNowService;
    private VulnerabilityService vulnerabilityService;
    private OutlookService outlookService;
    private PptReportService pptReportService;

    @BeforeEach
    void setUp() {
        dashboardService = Mockito.mock(DashboardService.class);
        vulnerabilityService = Mockito.mock(VulnerabilityService.class);

        ServiceNowProperties snowProps = new ServiceNowProperties();
        snowProps.setBaseUrl("https://schneider.service-now.com");
        snowProps.setAssignmentGroup("EI Services Global L2");
        snowProps.setAgingDays(5);
        serviceNowService = new ServiceNowService(snowProps);
        serviceNowService.init();

        OutlookProperties outlookProperties = new OutlookProperties();
        outlookService = new OutlookService(outlookProperties);

        pptReportService = new PptReportService(
                dashboardService,
                serviceNowService,
                vulnerabilityService,
                outlookService,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(pptReportService, "reportOutputDir", tempDir.toString());
        ReflectionTestUtils.setField(pptReportService, "configuredPythonPath", "python");

        // Mock Jira summary
        Map<String, Object> jiraSummary = Map.of(
                "total", 34,
                "statuses", List.of(
                        Map.of("status", "In Progress", "count", 12),
                        Map.of("status", "Ready for UAT", "count", 8),
                        Map.of("status", "Open", "count", 14)
                )
        );
        when(dashboardService.getJiraSummary(false)).thenReturn(jiraSummary);

        // Mock JFrog summary
        VulnModels.Counts totals = new VulnModels.Counts(2, 5, 8, 3, 0, 18, 12);
        VulnModels.Summary vulnSummary = new VulnModels.Summary(
                "OK", "Scan completed", 5, 5, null, totals, 2, 4, 4,
                List.of(
                        new VulnModels.ProjectSummary("mdm-integration", "https://github.com", "1.89.0", totals, 2, 2, List.of())
                )
        );
        when(vulnerabilityService.summary()).thenReturn(vulnSummary);
    }

    @Test
    void testGenerateExecutivePpt() {
        Map<String, Object> result = pptReportService.generateExecutivePpt(7, "Test Executive Operations Briefing");
        assertNotNull(result);
        assertEquals("SUCCESS", result.get("status"), "PowerPoint generation should succeed");
        assertTrue(result.containsKey("filename"));
        assertTrue(result.containsKey("downloadUrl"));
        assertTrue(result.containsKey("filePath"));

        String filename = (String) result.get("filename");
        assertTrue(filename.endsWith(".pptx"));

        @SuppressWarnings("unchecked")
        Map<String, Object> highlights = (Map<String, Object>) result.get("summaryHighlights");
        assertNotNull(highlights);
        assertEquals(34, highlights.get("jiraTotalActiveTickets"));
        assertTrue(((Number) highlights.get("serviceNowActiveIncidents")).intValue() > 0);
        assertTrue(((Number) highlights.get("outlookMajorIssuesReported")).intValue() > 0);
        assertEquals(18, highlights.get("jfrogSecurityVulnerabilitiesTotal"));
    }

    @Test
    void testGetReportAsResource() throws IOException {
        Map<String, Object> result = pptReportService.generateExecutivePpt(7, "Resource Retrieval Test");
        assertEquals("SUCCESS", result.get("status"));
        String filename = (String) result.get("filename");

        Resource resource = pptReportService.getReportAsResource(filename);
        assertNotNull(resource);
        assertTrue(resource.exists());
        assertTrue(resource.contentLength() > 0);
    }

    @Test
    void testGetReportAsResource_SecurityPathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> pptReportService.getReportAsResource("../secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> pptReportService.getReportAsResource("sub/path/file.pptx"));
    }
}
