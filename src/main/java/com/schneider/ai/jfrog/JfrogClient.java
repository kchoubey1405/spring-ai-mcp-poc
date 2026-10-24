package com.schneider.ai.jfrog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schneider.ai.jfrog.VulnModels.Counts;
import com.schneider.ai.jfrog.VulnModels.ImageScan;
import com.schneider.ai.jfrog.VulnModels.Issue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Thin REST client for JFrog Artifactory (docker catalog, tags, maven metadata) and Xray (artifact and component
 * summaries). Replaces the unavailable JFrog MCP server. Read-only.
 */
@Slf4j
@Component
public class JfrogClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final int SUMMARY_TEXT_MAX = 220;

    private final JfrogProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.schneider.ai.auth.SessionAuthService sessionAuthService;

    public JfrogClient(JfrogProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(props.getConnectTimeoutSeconds()).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(props.getReadTimeoutSeconds()).toMillis());
        if (props.getProxyHost() != null && !props.getProxyHost().isBlank() && props.getProxyPort() > 0) {
            factory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(props.getProxyHost(), props.getProxyPort())));
        }
        this.restTemplate = new RestTemplate(factory);
    }

    public String resolveToken(String sessionId) {
        if (sessionAuthService != null && sessionId != null && !sessionId.isBlank()) {
            Optional<String> token = sessionAuthService.getToken(sessionId, "jfrog");
            if (token.isPresent() && !token.get().isBlank()) {
                return token.get();
            }
            Optional<String> alt = sessionAuthService.getToken(sessionId, "artifactory");
            if (alt.isPresent() && !alt.get().isBlank()) {
                return alt.get();
            }
        }
        if (sessionAuthService != null) {
            String fb = sessionAuthService.getFallbackTokenForService("jfrog");
            if (fb != null && !fb.isBlank()) {
                return fb;
            }
        }
        return props.getToken() != null ? props.getToken().trim() : "";
    }

    public boolean isConfigured() {
        return !resolveToken(null).isBlank();
    }

    // ------------------------------------------------------------------ Artifactory (docker)

    public List<String> listImages() {
        JsonNode node = get("/artifactory/api/docker/" + props.getDockerRepo() + "/v2/_catalog?n=2000");
        List<String> images = new ArrayList<>();
        for (JsonNode name : node.path("repositories")) {
            images.add(name.asText());
        }
        Collections.sort(images);
        return images;
    }

    public List<String> listTags(String image) {
        JsonNode node = get("/artifactory/api/docker/" + props.getDockerRepo() + "/v2/" + image + "/tags/list");
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : node.path("tags")) {
            tags.add(tag.asText());
        }
        return tags;
    }

    // ------------------------------------------------------------------ Xray

    /** Scan result of a docker image tag, as shown in the Xray "Artifact summary". */
    public ImageScan scanImage(String image, String tag) {
        String path = "default/" + props.getDockerRepo() + "/" + image + "/" + tag + "/manifest.json";
        try {
            JsonNode root = post("/xray/api/v2/summary/artifact", Map.of("paths", List.of(path)));
            JsonNode artifacts = root.path("artifacts");
            if (!artifacts.isArray() || artifacts.isEmpty()) {
                String error = root.path("errors").isArray() && !root.path("errors").isEmpty()
                        ? root.path("errors").get(0).path("error").asText("not indexed in Xray")
                        : "not indexed in Xray";
                return ImageScan.failed(image, tag, VulnModels.STATUS_NOT_SCANNED, error);
            }
            return parseArtifact(image, tag, artifacts.get(0));
        } catch (Exception ex) {
            log.warn("Xray scan failed for {}:{} - {}", image, tag, ex.getMessage());
            return ImageScan.failed(image, tag, VulnModels.STATUS_ERROR, ex.getMessage());
        }
    }

    private ImageScan parseArtifact(String image, String tag, JsonNode artifact) {
        Map<String, Issue> unique = new LinkedHashMap<>();
        for (JsonNode issue : artifact.path("issues")) {
            JsonNode component = issue.path("components").isArray() && !issue.path("components").isEmpty()
                    ? issue.path("components").get(0) : mapper.createObjectNode();
            String issueId = issue.path("issue_id").asText("");
            String componentId = component.path("component_id").asText("");
            String version = component.path("version").asText("");
            String key = issueId + "|" + componentId + "|" + version;
            if (unique.containsKey(key)) {
                continue;
            }
            List<String> fixed = new ArrayList<>();
            for (JsonNode f : component.path("fixed_versions")) {
                fixed.add(f.asText());
            }
            String cve = issue.path("cves").isArray() && !issue.path("cves").isEmpty()
                    ? issue.path("cves").get(0).path("cve").asText("") : "";
            String impact = issue.path("impact_path").isArray() && !issue.path("impact_path").isEmpty()
                    ? lastSegments(issue.path("impact_path").get(0).asText(""), 3) : "";
            unique.put(key, new Issue(issueId, cve, normalizeSeverity(issue.path("severity").asText()),
                    abbreviate(issue.path("summary").asText(""), SUMMARY_TEXT_MAX), componentId, version,
                    component.path("pkg_type").asText(""), List.copyOf(fixed), impact));
        }
        List<Issue> issues = List.copyOf(unique.values());
        return new ImageScan(image, tag, VulnModels.STATUS_OK, "", countsOf(issues), issues, Instant.now());
    }

    /** Vulnerability counts of Maven components (gav = group:artifact:version) as indexed in Xray. */
    public Map<String, List<Issue>> componentIssues(Collection<String> gavs) {
        Map<String, List<Issue>> result = new LinkedHashMap<>();
        List<String> list = new ArrayList<>(gavs);
        for (int from = 0; from < list.size(); from += 10) {
            List<String> batch = list.subList(from, Math.min(list.size(), from + 10));
            List<Map<String, String>> details = new ArrayList<>();
            for (String gav : batch) {
                details.add(Map.of("component_id", "gav://" + gav));
            }
            JsonNode root = post("/xray/api/v1/summary/component", Map.of("component_details", details));
            for (JsonNode artifact : root.path("artifacts")) {
                String id = artifact.path("general").path("component_id").asText("").replace("gav://", "");
                List<Issue> issues = new ArrayList<>();
                for (JsonNode issue : artifact.path("issues")) {
                    JsonNode component = issue.path("components").isArray() && !issue.path("components").isEmpty()
                            ? issue.path("components").get(0) : mapper.createObjectNode();
                    List<String> fixed = new ArrayList<>();
                    for (JsonNode f : component.path("fixed_versions")) {
                        fixed.add(f.asText());
                    }
                    String cve = issue.path("cves").isArray() && !issue.path("cves").isEmpty()
                            ? issue.path("cves").get(0).path("cve").asText("") : "";
                    issues.add(new Issue(issue.path("issue_id").asText(""), cve,
                            normalizeSeverity(issue.path("severity").asText()),
                            abbreviate(issue.path("summary").asText(""), SUMMARY_TEXT_MAX),
                            component.path("component_id").asText(""), component.path("version").asText(""),
                            component.path("pkg_type").asText(""), List.copyOf(fixed), ""));
                }
                result.put(id, issues);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ Artifactory (maven)

    /** All versions published for a Maven artifact, in repository order (oldest first). */
    public List<String> mavenVersions(String groupId, String artifactId) {
        String path = "/artifactory/" + props.getMavenRepo() + "/" + groupId.replace('.', '/') + "/" + artifactId
                + "/maven-metadata.xml";
        byte[] body = exchange(path, HttpMethod.GET, null, byte[].class);
        List<String> versions = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            NodeList nodes = doc.getElementsByTagName("version");
            for (int i = 0; i < nodes.getLength(); i++) {
                String v = nodes.item(i).getTextContent().trim();
                if (!v.isEmpty()) {
                    versions.add(v);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse maven-metadata.xml for " + groupId + ":" + artifactId, ex);
        }
        return versions;
    }

    // ------------------------------------------------------------------ helpers

    public static Counts countsOf(Collection<Issue> issues) {
        int critical = 0, high = 0, medium = 0, low = 0, unknown = 0, fixable = 0;
        for (Issue issue : issues) {
            switch (issue.severity()) {
                case "Critical" -> critical++;
                case "High" -> high++;
                case "Medium" -> medium++;
                case "Low" -> low++;
                default -> unknown++;
            }
            if (issue.fixedVersions() != null && !issue.fixedVersions().isEmpty()) {
                fixable++;
            }
        }
        return new Counts(critical, high, medium, low, unknown, issues.size(), fixable);
    }

    static String normalizeSeverity(String severity) {
        if (severity == null) {
            return "Unknown";
        }
        return switch (severity.trim().toLowerCase(Locale.ROOT)) {
            case "critical" -> "Critical";
            case "high" -> "High";
            case "medium" -> "Medium";
            case "low" -> "Low";
            default -> "Unknown";
        };
    }

    private static String abbreviate(String text, int max) {
        String single = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return single.length() <= max ? single : single.substring(0, max - 3) + "...";
    }

    private static String lastSegments(String path, int count) {
        String[] parts = path.split("/");
        int from = Math.max(0, parts.length - count);
        return String.join("/", Arrays.copyOfRange(parts, from, parts.length));
    }

    private JsonNode get(String path) {
        return readTree(exchange(path, HttpMethod.GET, null, byte[].class));
    }

    private JsonNode post(String path, Object body) {
        return readTree(exchange(path, HttpMethod.POST, body, byte[].class));
    }

    private JsonNode readTree(byte[] bytes) {
        try {
            return mapper.readTree(bytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Unexpected non-JSON response from JFrog: " + ex.getMessage(), ex);
        }
    }

    private <T> T exchange(String path, HttpMethod method, Object body, Class<T> type) {
        String token = resolveToken(null);
        if (token.isBlank()) {
            throw new IllegalStateException("JFrog is not configured: set JFROG_ACCESS_TOKEN in .env or connect via MCP modal.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML,
                MediaType.ALL));
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return restTemplate.exchange(props.getBaseUrl() + path, method, new HttpEntity<>(body, headers), type)
                        .getBody();
            } catch (HttpStatusCodeException ex) {
                int status = ex.getStatusCode().value();
                last = new IllegalStateException("JFrog " + method + " " + path + " failed: HTTP " + status
                        + (status == 401 || status == 403 ? " (check JFROG_ACCESS_TOKEN permissions)" : ""), ex);
                if (status < 500 && status != 429) {
                    throw last;
                }
            } catch (RuntimeException ex) {
                last = ex;
            }
            sleep(attempt * 1500L);
        }
        throw last;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
