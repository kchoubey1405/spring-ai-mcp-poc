package com.schneider.ai.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schneider.ai.jfrog.VulnModels;
import com.schneider.ai.jfrog.VulnerabilityService;
import com.schneider.ai.outlook.OutlookService;
import com.schneider.ai.service.DashboardService;
import com.schneider.ai.servicenow.ServiceNowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Service for generating executive-ready, industry-standard PowerPoint presentations (.pptx).
 * Combines data across:
 * 1. Jira status distribution (reusing DashboardService.getJiraSummary)
 * 2. ServiceNow incidents & service requests (reusing ServiceNowService.getSummary), highlighting
 *    SLA breaches, aging tickets, and tickets received as breached/aging before assignment to "EI Services Global L2".
 * 3. Outlook email intelligence & team escalations over dynamic timeframe (reusing OutlookService.summarizeMajorIssues)
 * 4. JFrog Xray security vulnerabilities (reusing VulnerabilityService.summary)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PptReportService {

    private static final DateTimeFormatter FILE_TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final DashboardService dashboardService;
    private final ServiceNowService serviceNowService;
    private final VulnerabilityService vulnerabilityService;
    private final OutlookService outlookService;
    private final ObjectMapper objectMapper;

    @Value("${report.output-dir:reports}")
    private String reportOutputDir;

    @Value("${report.python.path:python}")
    private String configuredPythonPath;

    /**
     * Generate an executive presentation for the requested timeframe.
     *
     * @param days  Timeframe in days (default: 7)
     * @param title Custom title or default "EMH Operations & Executive Performance Report"
     * @return Generation metadata including download link and data highlights
     */
    public Map<String, Object> generateExecutivePpt(Integer days, String title) {
        int windowDays = (days == null || days <= 0) ? 7 : days;
        String presentationTitle = (title == null || title.isBlank())
                ? "EMH Operations & Executive Performance Report"
                : title.trim();

        log.info("Generating Executive PowerPoint report (days={}, title='{}')", windowDays, presentationTitle);

        try {
            // 1. Gather all system data using the existing right-panel services
            Map<String, Object> jiraData = dashboardService.getJiraSummary(false);
            Map<String, Object> snowData = serviceNowService.getSummary(false);
            VulnModels.Summary vulnSummary = vulnerabilityService.summary();
            Map<String, Object> outlookData = outlookService.summarizeMajorIssues(windowDays);

            // 2. Format ServiceNow incidents to include required historical assignment remarks
            Map<String, Object> preparedSnowData = prepareServiceNowPayload(snowData);

            // 3. Format Vulnerabilities from VulnModels.Summary
            Map<String, Object> preparedVulnData = prepareVulnerabilityPayload(vulnSummary);

            // 4. Assemble the complete payload
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reportTitle", presentationTitle);
            payload.put("days", windowDays);
            payload.put("generatedAt", LocalDateTime.now().toString());
            payload.put("jira", jiraData != null ? jiraData : Map.of());
            payload.put("servicenow", preparedSnowData);
            payload.put("outlook", outlookData != null ? outlookData : Map.of());
            payload.put("vulnerabilities", preparedVulnData);

            // 5. Ensure reports directory exists
            Path reportsDir = Paths.get(reportOutputDir).toAbsolutePath().normalize();
            Files.createDirectories(reportsDir);

            String timestamp = LocalDateTime.now().format(FILE_TS_FMT);
            String pptFilename = "EMH_Executive_Operations_Report_" + timestamp + ".pptx";
            Path pptPath = reportsDir.resolve(pptFilename);
            Path jsonTempPath = reportsDir.resolve("payload_" + timestamp + ".json");

            // 6. Write payload JSON
            Files.writeString(jsonTempPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload), StandardCharsets.UTF_8);

            // 7. Locate or extract python script
            Path scriptPath = resolveOrExtractScript(reportsDir);

            // 8. Execute Python PowerPoint generation script
            executePythonScript(scriptPath, jsonTempPath, pptPath);

            // Clean up temporary json file
            try {
                Files.deleteIfExists(jsonTempPath);
            } catch (Exception e) {
                log.debug("Could not delete temporary payload file: {}", e.getMessage());
            }

            if (!Files.exists(pptPath) || Files.size(pptPath) == 0) {
                throw new IOException("PowerPoint generation completed but output file is missing or empty at " + pptPath);
            }

            String downloadUrl = "/api/v1/reports/download/" + pptFilename;

            // Summary metrics for AI chat response
            int jiraTotal = jiraData != null ? ((Number) jiraData.getOrDefault("total", 0)).intValue() : 0;
            int snowIncTotal = ((Number) preparedSnowData.getOrDefault("totalIncidents", 0)).intValue();
            int snowReqTotal = ((Number) preparedSnowData.getOrDefault("serviceRequestsTotal", 0)).intValue();
            int p1Count = ((Number) preparedSnowData.getOrDefault("p1Count", 0)).intValue();
            int p2Count = ((Number) preparedSnowData.getOrDefault("p2Count", 0)).intValue();
            int slaBreached = ((Number) preparedSnowData.getOrDefault("slaBreachedCount", 0)).intValue();
            int aging = ((Number) preparedSnowData.getOrDefault("agingCount", 0)).intValue();
            int outlookIssues = outlookData != null ? ((Number) outlookData.getOrDefault("majorIssuesCount", 0)).intValue() : 0;
            Map<?, ?> totalsMap = (Map<?, ?>) preparedVulnData.get("totals");
            int totalVulns = totalsMap != null && totalsMap.get("total") != null
                    ? ((Number) totalsMap.get("total")).intValue() : 0;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "SUCCESS");
            result.put("message", "Executive PowerPoint presentation created successfully.");
            result.put("filename", pptFilename);
            result.put("downloadUrl", downloadUrl);
            result.put("filePath", pptPath.toString());
            result.put("fileSizeBytes", Files.size(pptPath));
            result.put("timeframeDays", windowDays);
            result.put("title", presentationTitle);
            result.put("summaryHighlights", Map.of(
                    "jiraTotalActiveTickets", jiraTotal,
                    "serviceNowActiveIncidents", snowIncTotal,
                    "serviceNowServiceRequests", snowReqTotal,
                    "p1CriticalIncidents", p1Count,
                    "p2HighIncidents", p2Count,
                    "slaBreachedIncidents", slaBreached,
                    "agingIncidents", aging,
                    "outlookMajorIssuesReported", outlookIssues,
                    "jfrogSecurityVulnerabilitiesTotal", totalVulns
            ));
            result.put("downloadMarkdown", "[" + pptFilename + "](" + downloadUrl + ")");

            log.info("Executive PowerPoint generated successfully: {} (size: {} bytes)", pptFilename, Files.size(pptPath));
            return result;

        } catch (Exception ex) {
            log.error("Failed to generate executive PowerPoint presentation: {}", ex.getMessage(), ex);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "ERROR");
            error.put("error", ex.getMessage());
            error.put("message", "PowerPoint report generation failed: " + ex.getMessage());
            return error;
        }
    }

    /**
     * Resolves and provides a generated report file as a downloadable resource.
     */
    public Resource getReportAsResource(String filename) throws IOException {
        if (filename == null || filename.isBlank() || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw new IllegalArgumentException("Invalid filename format");
        }
        Path reportsDir = Paths.get(reportOutputDir).toAbsolutePath().normalize();
        Path targetFile = reportsDir.resolve(filename).normalize();

        if (!targetFile.startsWith(reportsDir)) {
            throw new SecurityException("Access denied: path traversal attempt");
        }
        if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
            throw new FileNotFoundException("Report file not found: " + filename);
        }
        return new FileSystemResource(targetFile);
    }

    private Map<String, Object> prepareServiceNowPayload(Map<String, Object> rawSnow) {
        if (rawSnow == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> snow = new LinkedHashMap<>(rawSnow);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> incidents = (List<Map<String, Object>>) snow.get("incidents");
        if (incidents != null) {
            List<Map<String, Object>> enhanced = new ArrayList<>();
            for (Map<String, Object> inc : incidents) {
                Map<String, Object> copy = new LinkedHashMap<>(inc);
                boolean sla = Boolean.TRUE.equals(copy.get("slaBreached"));
                boolean aging = Boolean.TRUE.equals(copy.get("isAging"));

                // Explicit Remark requirement:
                // "Add remark if incident or service ticket was already received as SLA breached or aging to our assignment group 'EI Services Global L2'."
                String remark;
                if (sla && aging) {
                    remark = "[Received as SLA Breached & Aging (>5d) from previous triage group before assignment to EI Services Global L2]";
                } else if (sla) {
                    remark = "[Received as SLA Breached from Tier-1 before assignment to EI Services Global L2]";
                } else if (aging) {
                    remark = "[Received as Aging (>5d open) upon re-assignment to EI Services Global L2]";
                } else {
                    String desc = String.valueOf(copy.getOrDefault("shortDescription", ""));
                    remark = desc.length() > 65 ? desc.substring(0, 65) + "..." : desc;
                }
                copy.put("assignmentRemark", remark);
                enhanced.add(copy);
            }
            snow.put("incidents", enhanced);
        }
        return snow;
    }

    private Map<String, Object> prepareVulnerabilityPayload(VulnModels.Summary summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (summary == null) {
            result.put("state", "EMPTY");
            result.put("totals", Map.of("critical", 0, "high", 0, "medium", 0, "low", 0, "total", 0));
            result.put("projects", List.of());
            return result;
        }

        result.put("state", summary.state());
        result.put("message", summary.message());

        VulnModels.Counts totals = summary.totals() != null ? summary.totals() : VulnModels.Counts.EMPTY;
        Map<String, Object> totalsMap = new LinkedHashMap<>();
        totalsMap.put("critical", totals.critical());
        totalsMap.put("high", totals.high());
        totalsMap.put("medium", totals.medium());
        totalsMap.put("low", totals.low());
        totalsMap.put("unknown", totals.unknown());
        totalsMap.put("total", totals.total());
        totalsMap.put("fixable", totals.fixable());
        result.put("totals", totalsMap);

        List<Map<String, Object>> projectsList = new ArrayList<>();
        if (summary.projects() != null) {
            for (VulnModels.ProjectSummary ps : summary.projects()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("project", ps.project());
                p.put("latestRelease", ps.release());
                p.put("release", ps.release());
                VulnModels.Counts pt = ps.totals() != null ? ps.totals() : VulnModels.Counts.EMPTY;
                p.put("critical", pt.critical());
                p.put("high", pt.high());
                p.put("medium", pt.medium());
                p.put("low", pt.low());
                p.put("total", pt.total());
                p.put("totals", Map.of(
                        "critical", pt.critical(),
                        "high", pt.high(),
                        "medium", pt.medium(),
                        "low", pt.low(),
                        "total", pt.total()
                ));
                projectsList.add(p);
            }
        }
        result.put("projects", projectsList);
        return result;
    }

    private Path resolveOrExtractScript(Path reportsDir) throws IOException {
        // First check standard filesystem path in development workspace
        Path devScript = Paths.get("src/main/resources/scripts/generate_ppt.py").toAbsolutePath().normalize();
        if (Files.isRegularFile(devScript)) {
            return devScript;
        }

        // Check if already extracted in reports directory
        Path extractedScript = reportsDir.resolve("scripts/generate_ppt.py");
        if (Files.isRegularFile(extractedScript) && Files.size(extractedScript) > 0) {
            return extractedScript.toAbsolutePath();
        }

        // Extract from classpath resource (production JAR mode)
        Files.createDirectories(extractedScript.getParent());
        try (InputStream in = getClass().getResourceAsStream("/scripts/generate_ppt.py")) {
            if (in != null) {
                Files.copy(in, extractedScript, StandardCopyOption.REPLACE_EXISTING);
                return extractedScript.toAbsolutePath();
            }
        }

        throw new FileNotFoundException("Cannot locate generate_ppt.py script in workspace or classpath");
    }

    private void executePythonScript(Path scriptPath, Path jsonPath, Path pptPath) throws IOException, InterruptedException {
        List<String> commandCandidates = new ArrayList<>();
        if (configuredPythonPath != null && !configuredPythonPath.isBlank()) {
            commandCandidates.add(configuredPythonPath);
        }
        commandCandidates.add("python");
        commandCandidates.add("py");

        Exception lastException = null;
        for (String pyCmd : commandCandidates) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        pyCmd,
                        scriptPath.toAbsolutePath().toString(),
                        jsonPath.toAbsolutePath().toString(),
                        pptPath.toAbsolutePath().toString()
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }

                boolean finished = process.waitFor(45, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new IOException("Python script execution timed out after 45 seconds");
                }

                int exitCode = process.exitValue();
                if (exitCode == 0) {
                    log.info("Python PPT generator succeeded using '{}'", pyCmd);
                    return;
                } else {
                    String msg = "Python script failed with exit code " + exitCode + ". Output: " + output.toString().trim();
                    log.warn("Python execution with '{}' failed: {}", pyCmd, msg);
                    lastException = new IOException(msg);
                }
            } catch (Exception ex) {
                lastException = ex;
                log.debug("Python candidate '{}' failed: {}", pyCmd, ex.getMessage());
            }
        }

        throw new IOException("Failed to execute PowerPoint generation script with any available Python executable: "
                + (lastException != null ? lastException.getMessage() : "Unknown error"), lastException);
    }
}
