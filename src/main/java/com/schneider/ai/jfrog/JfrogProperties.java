package com.schneider.ai.jfrog;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Settings for the JFrog Artifactory / Xray REST integration (the JFrog MCP server is not enabled in the organisation,
 * so everything is done through the REST APIs).
 */
@Data
@Component
@ConfigurationProperties(prefix = "jfrog")
public class JfrogProperties {

    private String baseUrl = "https://global-artifacts.se.com";

    /** Access token (sent as Bearer). */
    private String token = "";

    /** Docker repository that holds the integration images (one image per module). */
    private String dockerRepo = "sd-ei-emh-eu-docker-prod-fed";

    /** Registry host used in Dockerfile / Jib image references. */
    private String dockerHost = "global-artifacts.se.com";

    /** Maven repository (virtual) used to look up dependency versions. */
    private String mavenRepo = "libs-release-prod-virtual";

    private int cacheTtlMinutes = 360;
    private int parallelism = 6;
    private boolean startupRefresh = true;
    private int connectTimeoutSeconds = 20;
    private int readTimeoutSeconds = 180;

    /** Optional HTTP proxy for the JFrog calls (leave empty for a direct connection). */
    private String proxyHost = "";
    private int proxyPort = 0;

    /** Manual image-to-project overrides, e.g. jfrog.image-project-overrides.lsm-processor=lsm-integration. */
    private Map<String, String> imageProjectOverrides = new HashMap<>();

    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }
}
