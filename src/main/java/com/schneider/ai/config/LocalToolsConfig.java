package com.schneider.ai.config;

import com.schneider.ai.tools.JfrogTools;
import com.schneider.ai.tools.KafkaTools;
import com.schneider.ai.tools.MavenTools;
import com.schneider.ai.tools.OutlookTools;
import com.schneider.ai.tools.ReportTools;
import com.schneider.ai.tools.ServiceNowTools;
import com.schneider.ai.tools.WorkspaceTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the in-process tools (JFrog/Xray, Maven Central lookup, sandboxed workspace,
 * Kafka Connect operations, ServiceNow operations, Outlook email intelligence, and Executive Reports) as a
 * {@link ToolCallbackProvider}. The orchestrator merges these with the tools discovered from the MCP servers.
 */
@Configuration
public class LocalToolsConfig {

    @Bean
    public ToolCallbackProvider localToolCallbackProvider(JfrogTools jfrogTools, MavenTools mavenTools,
                                                          WorkspaceTools workspaceTools, KafkaTools kafkaTools,
                                                          ServiceNowTools serviceNowTools, OutlookTools outlookTools,
                                                          ReportTools reportTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(jfrogTools, mavenTools, workspaceTools, kafkaTools, serviceNowTools, outlookTools, reportTools)
                .build();
    }
}
