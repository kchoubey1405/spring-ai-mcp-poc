package com.schneider.ai.controller;

import com.schneider.ai.report.PptReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * REST controller for operations reports and PowerPoint presentations.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final PptReportService pptReportService;

    /**
     * Generate an executive PowerPoint presentation across Jira, ServiceNow, Outlook, and JFrog Xray.
     * Example: GET /api/v1/reports/ppt?days=7&title=EMH+Weekly+Ops+Review
     */
    @GetMapping("/ppt")
    public ResponseEntity<Map<String, Object>> generatePpt(
            @RequestParam(name = "days", defaultValue = "7") int days,
            @RequestParam(name = "title", required = false) String title) {
        log.info("API request to generate Executive PPT (days={}, title={})", days, title);
        Map<String, Object> result = pptReportService.generateExecutivePpt(days, title);
        if ("ERROR".equals(result.get("status"))) {
            return ResponseEntity.internalServerError().body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Download a generated presentation or report file.
     * Example: GET /api/v1/reports/download/EMH_Executive_Operations_Report_20261022_164500.pptx
     */
    @GetMapping("/download/{filename:.+}")
    public ResponseEntity<Resource> downloadReport(@PathVariable("filename") String filename) throws IOException {
        log.info("Download request for report file: {}", filename);
        Resource resource = pptReportService.getReportAsResource(filename);
        long contentLength = resource.contentLength();

        MediaType mediaType = MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        );

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(contentLength)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(resource);
    }
}
