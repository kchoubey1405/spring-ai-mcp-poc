package com.schneider.ai.tools;

import com.schneider.ai.report.PptReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Spring AI Tools for generating executive reports and PowerPoint (.pptx) presentations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportTools {

    private final PptReportService pptReportService;

    @Tool(name = "report_generateExecutivePpt", description = """
            Generate an executive, industry-standard PowerPoint presentation (.pptx) with Schneider Electric corporate styling,
            native bar charts, metric KPI cards, and detailed tables.
            Includes:
            1. Executive Cover slide with metadata
            2. Jira status-wise ticket breakdown (reusing right-panel UI method) with native horizontal bar chart
            3. ServiceNow Incidents & Service Requests for 'EI Services Global L2', highlighting SLA breached and aging (>5d) items,
               with explicit remarks for tickets received as SLA breached or aging before assignment.
            4. Dynamic Outlook Email Intelligence summarizing major operational issues reported by any team (Infra, DB, Kafka, Security) over the requested days.
            5. Security & Vulnerability posture (reusing UI right-panel JFrog Xray calculation).
            Returns the direct download link and executive metric highlights.
            """)
    public Map<String, Object> generateExecutivePpt(
            @ToolParam(required = false, description = "Dynamic date range in days for Outlook and system review (e.g. 7 for past week, 14, 30). Default: 7") Integer days,
            @ToolParam(required = false, description = "Presentation title. Default: 'EMH Operations & Executive Performance Report'") String title) {
        log.info("Tool report_generateExecutivePpt invoked (days={}, title={})", days, title);
        return pptReportService.generateExecutivePpt(days != null ? days : 7, title);
    }
}
