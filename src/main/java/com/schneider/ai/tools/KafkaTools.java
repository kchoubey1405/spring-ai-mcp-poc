package com.schneider.ai.tools;

import com.schneider.ai.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tools for Kafka Connect operations: monitoring connector health, listing failed tasks,
 * and restarting failed connector tasks using the Kafka Connect REST API.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaTools {

    private final DashboardService dashboardService;

    @Tool(name = "kafka_restartConnectorTask", description = """
            Restart a specific failed task of a Kafka Connect connector using the REST API:
            POST /connectors/{connectorName}/tasks/{taskId}/restart
            """)
    public Map<String, Object> restartConnectorTask(
            @ToolParam(description = "Kafka connector name, e.g. OracleAqSinkConnector") String connector,
            @ToolParam(description = "Task ID integer to restart, e.g. 2") int taskId) {
        log.info("Agent tool executing restart of Kafka connector {} task {}", connector, taskId);
        return dashboardService.restartKafkaTask(connector, taskId);
    }

    @Tool(name = "kafka_getConnectorsSummary", description = """
            Get the current operational summary of all Kafka Connect connectors:
            total count, running, paused, failed counts, and details of non-running/failed connectors and tasks.
            """)
    public Map<String, Object> getConnectorsSummary(
            @ToolParam(required = false, description = "true to force refresh from Kafka Connect cluster") Boolean refresh) {
        return dashboardService.getKafkaSummary(Boolean.TRUE.equals(refresh));
    }

    @Tool(name = "kafka_restartFailedTasks", description = """
            Restart all failed tasks for a given Kafka connector, or across all connectors if connector name is not specified.
            """)
    public Map<String, Object> restartFailedTasks(
            @ToolParam(required = false, description = "Kafka connector name, e.g. OracleAqSinkConnector. If omitted, restarts failed tasks across all connectors.") String connector) {
        log.info("Agent tool executing restart of all failed tasks for connector {}", connector);
        return dashboardService.restartFailedTasks(connector);
    }
}
