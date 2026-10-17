package com.schneider.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Data for the right-hand context panel of the UI:
 * <ul>
 *   <li>EMH Jira tickets, counted per status</li>
 *   <li>open pull requests of every repository of the GitHub Enterprise organisation</li>
 *   <li>Kafka Connect connectors: total and running / paused / failed</li>
 * </ul>
 * Every section is cached for a few minutes (one loader at a time) and warmed when the application starts. A section
 * that cannot be loaded reports status ERROR with the reason - it never returns made-up numbers.
 * Vulnerability data (JFrog Xray) lives in com.schneider.ai.jfrog.VulnerabilityService.
 */
@Slf4j
@Service
public class DashboardService {

    /** Same filter as the chat default for "EMH Jira": EMH components/labels, closed and production work excluded. */
    static final String DEFAULT_EMH_JQL = "(Components in (\"EMH\", \"EMH-Middleware\") OR labels in (\"EMH\", \"EMH-Middleware\")) "
            + "AND status NOT IN (\"Closed\", \"In Production\", \"Pushed to Production\", \"Cancelled\")";

    private static final int JIRA_BATCH = 500;
    private static final int JIRA_MAX_BATCHES = 20;
    private static final int SEARCH_PAGE = 100;
    private static final int SEARCH_MAX_PAGES = 10;      // GitHub search returns at most 1000 results
    private static final int PARALLELISM = 8;

    private final RestTemplate restTemplate;

    @Value("${dashboard.github.base-url:https://github.schneider-electric.com/api/v3}")
    private String githubBaseUrl = "https://github.schneider-electric.com/api/v3";

    @Value("${dashboard.github.web-url:https://github.schneider-electric.com}")
    private String githubWebUrl = "https://github.schneider-electric.com";

    @Value("${dashboard.github.org:ifw-microservices}")
    private String githubOrg = "ifw-microservices";

    @Value("${dashboard.github.token:}")
    private String githubToken = "";

    @Value("${dashboard.kafka.base-url:https://uat.emh.ei.se.com/api/kafka-connect-1}")
    private String kafkaConnectUrl = "https://uat.emh.ei.se.com/api/kafka-connect-1";

    @Value("${dashboard.kafka.auth:}")
    private String kafkaAuth = "";

    @Value("${dashboard.jira.base-url:https://jira.se.com}")
    private String jiraBaseUrl = "https://jira.se.com";

    @Value("${dashboard.jira.token:}")
    private String jiraToken = "";

    @Value("${dashboard.jira.jql:}")
    private String jiraJql = "";

    private final Section jira;
    private final Section pullRequests;
    private final Section kafka;

    public DashboardService(@Value("${dashboard.cache-ttl-seconds:300}") long ttlSeconds) {
        this(ttlSeconds, true, true, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DashboardService(
            @Value("${dashboard.cache-ttl-seconds:300}") long ttlSeconds,
            @Value("${dashboard.tls.trust-all:true}") boolean trustAllCerts,
            @Value("${dashboard.tls.use-os-store:true}") boolean useOsStore,
            @Value("${dashboard.tls.trust-store:}") String trustStorePath,
            @Value("${dashboard.tls.trust-store-password:}") String trustStorePassword) {
        SimpleClientHttpRequestFactory factory = createSecureRequestFactory(trustAllCerts, useOsStore, trustStorePath, trustStorePassword);
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(45_000);
        this.restTemplate = new RestTemplate(factory);
        Duration ttl = Duration.ofSeconds(Math.max(5, ttlSeconds));
        this.jira = new Section("jira", ttl);
        this.pullRequests = new Section("pull requests", ttl);
        this.kafka = new Section("kafka", ttl);
    }

    private static SimpleClientHttpRequestFactory createSecureRequestFactory(
            boolean trustAll, boolean useOsStore, String trustStorePath, String trustStorePassword) {
        try {
            SSLContext sslContext = trustAll
                    ? TlsTrust.insecureContext()
                    : TlsTrust.context(useOsStore, trustStorePath, trustStorePassword);

            return new SimpleClientHttpRequestFactory() {
                @Override
                protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                    super.prepareConnection(connection, httpMethod);
                    if (connection instanceof HttpsURLConnection https) {
                        https.setSSLSocketFactory(sslContext.getSocketFactory());
                        if (trustAll) {
                            https.setHostnameVerifier((hostname, session) -> true);
                        }
                    }
                }
            };
        } catch (Exception ex) {
            log.warn("Could not configure TLS trust for DashboardService: {}", ex.getMessage());
            return new SimpleClientHttpRequestFactory();
        }
    }

    /** Loads all sections in the background right after startup so the first page load is instant. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        Thread thread = new Thread(() -> {
            getKafkaSummary(false);
            getJiraSummary(false);
            getOpenPullRequests(false);
        }, "dashboard-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    // ================================================================== public API

    public Map<String, Object> getJiraSummary(boolean force) {
        return jira.get(force, this::loadJira);
    }

    public Map<String, Object> getOpenPullRequests(boolean force) {
        return pullRequests.get(force, this::loadPullRequests);
    }

    public Map<String, Object> getKafkaSummary(boolean force) {
        return kafka.get(force, this::loadKafka);
    }

    // ================================================================== JIRA

    private Map<String, Object> loadJira() {
        String jql = jiraJql == null || jiraJql.isBlank() ? DEFAULT_EMH_JQL : jiraJql.trim();
        List<JsonNode> issues = new ArrayList<>();
        int total = 0;
        int startAt = 0;
        for (int batch = 0; batch < JIRA_MAX_BATCHES; batch++) {
            URI uri = UriComponentsBuilder.fromUriString(jiraBaseUrl.replaceAll("/+$", "") + "/rest/api/2/search")
                    .queryParam("jql", "{jql}")
                    .queryParam("fields", "status")
                    .queryParam("startAt", startAt)
                    .queryParam("maxResults", JIRA_BATCH)
                    .encode().buildAndExpand(jql).toUri();
            JsonNode body = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(jiraHeaders()), JsonNode.class).getBody();
            if (body == null) {
                break;
            }
            total = body.path("total").asInt(0);
            JsonNode page = body.path("issues");
            if (!page.isArray() || page.isEmpty()) {
                break;
            }
            page.forEach(issues::add);
            startAt += page.size();
            if (startAt >= total) {
                break;
            }
        }

        List<Map<String, Object>> statuses = new ArrayList<>();
        countByStatus(issues).forEach((name, count) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", name);
            row.put("count", count);
            statuses.add(row);
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("counted", issues.size());
        result.put("statuses", statuses);
        result.put("jql", jql);
        result.put("url", jiraBaseUrl.replaceAll("/+$", "") + "/issues/?jql="
                + java.net.URLEncoder.encode(jql, StandardCharsets.UTF_8).replace("+", "%20"));
        return result;
    }

    /** Tickets per status name, biggest group first. Excludes Closed and Production statuses. */
    static Map<String, Integer> countByStatus(List<JsonNode> issues) {
        Map<String, Integer> raw = new LinkedHashMap<>();
        for (JsonNode issue : issues) {
            String status = issue.path("fields").path("status").path("name").asText("Unknown");
            if (isPushedToProductionStatus(status)) {
                continue;
            }
            raw.merge(status, 1, Integer::sum);
        }
        return sortedByCount(raw);
    }

    static boolean isPushedToProductionStatus(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("pushed to production")
                || normalized.contains("in production")
                || normalized.equals("production");
    }

    // ================================================================== GITHUB: open pull requests

    private Map<String, Object> loadPullRequests() {
        Map<String, Integer> perRepo;
        int total;
        try {
            Map.Entry<Integer, Map<String, Integer>> found = searchOpenPullRequests();
            total = found.getKey();
            perRepo = found.getValue();
        } catch (Exception ex) {
            log.warn("GitHub search for open pull requests failed ({}); counting repository by repository", ex.getMessage());
            perRepo = countPerRepository();
            total = perRepo.values().stream().mapToInt(Integer::intValue).sum();
        }

        List<Map<String, Object>> projects = new ArrayList<>();
        sortedByCount(perRepo).forEach((repo, count) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("project", repo);
            row.put("openPRs", count);
            row.put("url", githubWebUrl.replaceAll("/+$", "") + "/" + githubOrg + "/" + repo + "/pulls");
            projects.add(row);
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("projectCount", projects.size());
        result.put("projects", projects);
        result.put("org", githubOrg);
        return result;
    }

    /** One search over the whole organisation ("is:pr is:open org:X"), grouped by repository. */
    private Map.Entry<Integer, Map<String, Integer>> searchOpenPullRequests() {
        Map<String, Integer> perRepo = new LinkedHashMap<>();
        int total = 0;
        int collected = 0;
        for (int page = 1; page <= SEARCH_MAX_PAGES; page++) {
            URI uri = UriComponentsBuilder.fromUriString(githubBaseUrl.replaceAll("/+$", "") + "/search/issues")
                    .queryParam("q", "{q}")
                    .queryParam("per_page", SEARCH_PAGE)
                    .queryParam("page", page)
                    .encode().buildAndExpand("is:pr is:open org:" + githubOrg).toUri();
            JsonNode body = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(githubHeaders()), JsonNode.class).getBody();
            if (body == null) {
                throw new IllegalStateException("empty search response");
            }
            total = body.path("total_count").asInt(0);
            JsonNode items = body.path("items");
            if (!items.isArray() || items.isEmpty()) {
                break;
            }
            for (JsonNode item : items) {
                perRepo.merge(repoOf(item.path("repository_url").asText("")), 1, Integer::sum);
            }
            collected += items.size();
            if (collected >= total) {
                break;
            }
        }
        if (collected < total) {
            throw new IllegalStateException("search returned only " + collected + " of " + total + " results");
        }
        return Map.entry(total, perRepo);
    }

    /** Fallback when search is unavailable or the organisation has more than 1000 open pull requests. */
    private Map<String, Integer> countPerRepository() {
        Map<String, Integer> perRepo = new LinkedHashMap<>();
        List<String> repos = new ArrayList<>();
        String api = githubBaseUrl.replaceAll("/+$", "");
        for (int page = 1; page <= 20; page++) {
            URI uri = UriComponentsBuilder.fromUriString(api + "/orgs/" + githubOrg + "/repos")
                    .queryParam("per_page", 100).queryParam("page", page).queryParam("type", "all").build().toUri();
            JsonNode body = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(githubHeaders()), JsonNode.class).getBody();
            if (body == null || !body.isArray() || body.isEmpty()) {
                break;
            }
            body.forEach(repo -> repos.add(repo.path("name").asText()));
            if (body.size() < 100) {
                break;
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM);
        try {
            Map<String, Future<Integer>> futures = new LinkedHashMap<>();
            for (String repo : repos) {
                futures.put(repo, pool.submit(() -> countOpenPullRequests(api, repo)));
            }
            for (Map.Entry<String, Future<Integer>> entry : futures.entrySet()) {
                try {
                    int count = entry.getValue().get();
                    if (count > 0) {
                        perRepo.put(entry.getKey(), count);
                    }
                } catch (Exception ex) {
                    log.debug("Could not count pull requests of {}: {}", entry.getKey(), ex.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return perRepo;
    }

    private int countOpenPullRequests(String api, String repo) {
        int count = 0;
        for (int page = 1; page <= 50; page++) {
            URI uri = UriComponentsBuilder.fromUriString(api + "/repos/" + githubOrg + "/" + repo + "/pulls")
                    .queryParam("state", "open").queryParam("per_page", 100).queryParam("page", page).build().toUri();
            JsonNode body = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(githubHeaders()), JsonNode.class).getBody();
            int size = body != null && body.isArray() ? body.size() : 0;
            count += size;
            if (size < 100) {
                break;
            }
        }
        return count;
    }

    static String repoOf(String repositoryUrl) {
        int slash = repositoryUrl.lastIndexOf('/');
        return slash >= 0 ? repositoryUrl.substring(slash + 1) : repositoryUrl;
    }

    private static Map<String, Integer> sortedByCount(Map<String, Integer> counts) {
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    // ================================================================== KAFKA CONNECT

    private Map<String, Object> loadKafka() {
        String base = kafkaConnectUrl.replaceAll("/+$", "");
        HttpEntity<Void> entity = new HttpEntity<>(kafkaHeaders());

        // 1. list of connectors
        JsonNode names = restTemplate.exchange(URI.create(base + "/connectors"), HttpMethod.GET, entity, JsonNode.class).getBody();
        List<String> connectors = new ArrayList<>();
        if (names != null && names.isArray()) {
            names.forEach(name -> connectors.add(name.asText()));
        }

        // 2. status of every connector
        List<Map<String, Object>> rows = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM);
        try {
            Map<String, Future<JsonNode>> futures = new LinkedHashMap<>();
            for (String connector : connectors) {
                futures.put(connector, pool.submit(() -> restTemplate.exchange(
                        UriComponentsBuilder.fromUriString(base + "/connectors/{name}/status").encode().buildAndExpand(connector).toUri(),
                        HttpMethod.GET, entity, JsonNode.class).getBody()));
            }
            for (Map.Entry<String, Future<JsonNode>> entry : futures.entrySet()) {
                JsonNode status = null;
                String error = null;
                try {
                    status = entry.getValue().get();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    error = cause.getMessage();
                    log.debug("Status of connector {} unavailable: {}", entry.getKey(), error);
                }
                rows.add(connectorRow(entry.getKey(), status, error));
            }
        } finally {
            pool.shutdownNow();
        }

        Map<String, Integer> counts = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            counts.merge(String.valueOf(row.get("health")), 1, Integer::sum);
        }
        List<Map<String, Object>> notRunning = rows.stream()
                .filter(row -> !"RUNNING".equals(row.get("health")))
                .sorted(Comparator.comparing((Map<String, Object> row) -> healthRank(String.valueOf(row.get("health"))))
                        .thenComparing(row -> String.valueOf(row.get("name"))))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", rows.size());
        result.put("running", counts.getOrDefault("RUNNING", 0));
        result.put("paused", counts.getOrDefault("PAUSED", 0));
        result.put("failed", counts.getOrDefault("FAILED", 0));
        result.put("other", counts.getOrDefault("OTHER", 0) + counts.getOrDefault("UNKNOWN", 0));
        result.put("notRunning", notRunning);
        return result;
    }

    private static Map<String, Object> connectorRow(String name, JsonNode status, String error) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        if (status == null) {
            row.put("health", "UNKNOWN");
            row.put("state", "UNKNOWN");
            row.put("reason", error == null ? "no status returned" : error);
            return row;
        }
        row.put("health", classify(status));
        row.put("state", status.path("connector").path("state").asText("UNKNOWN"));
        List<String> failedTasks = new ArrayList<>();
        int tasks = 0;
        for (JsonNode task : status.path("tasks")) {
            tasks++;
            if ("FAILED".equalsIgnoreCase(task.path("state").asText())) {
                failedTasks.add("task " + task.path("id").asText("?"));
            }
        }
        row.put("tasks", tasks);
        row.put("failedTasks", failedTasks);
        return row;
    }

    /**
     * Health of one connector from its /status document:
     * FAILED when the connector or ANY of its tasks failed (even if the other tasks still run), PAUSED when the
     * connector is paused, RUNNING when it runs, OTHER for UNASSIGNED / RESTARTING / anything else.
     */
    static String classify(JsonNode status) {
        if (status == null) {
            return "UNKNOWN";
        }
        String state = status.path("connector").path("state").asText("").toUpperCase(Locale.ROOT);
        if ("FAILED".equals(state)) {
            return "FAILED";
        }
        for (JsonNode task : status.path("tasks")) {
            if ("FAILED".equalsIgnoreCase(task.path("state").asText())) {
                return "FAILED";
            }
        }
        return switch (state) {
            case "RUNNING" -> "RUNNING";
            case "PAUSED" -> "PAUSED";
            default -> "OTHER";
        };
    }

    private static int healthRank(String health) {
        return switch (health) {
            case "FAILED" -> 0;
            case "PAUSED" -> 1;
            default -> 2;
        };
    }

    // ================================================================== HTTP headers

    private HttpHeaders jiraHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        if (jiraToken != null && !jiraToken.isBlank()) {
            headers.set("Authorization", "Bearer " + jiraToken);   // Jira Data Center personal access token
        }
        return headers;
    }

    private HttpHeaders githubHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github.v3+json");
        if (githubToken != null && !githubToken.isBlank()) {
            headers.set("Authorization", "token " + githubToken);
        }
        return headers;
    }

    private HttpHeaders kafkaHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        if (kafkaAuth != null && !kafkaAuth.isBlank()) {
            headers.set("Authorization", kafkaAuth);
        }
        return headers;
    }

    // ================================================================== cache

    /** One cached dashboard section: fresh for the TTL, loaded by one thread at a time, last good value kept on errors. */
    static final class Section {
        private final String name;
        private final Duration ttl;
        private final Object lock = new Object();
        private volatile Map<String, Object> value;
        private volatile Instant loadedAt;

        Section(String name, Duration ttl) {
            this.name = name;
            this.ttl = ttl;
        }

        Map<String, Object> get(boolean force, Supplier<Map<String, Object>> loader) {
            Map<String, Object> current = value;
            if (!force && current != null && fresh()) {
                return current;
            }
            synchronized (lock) {
                if (!force && value != null && fresh()) {
                    return value;
                }
                try {
                    Map<String, Object> loaded = new LinkedHashMap<>(loader.get());
                    loaded.put("status", "READY");
                    loaded.put("stale", false);
                    loaded.put("fetchedAt", Instant.now().toString());
                    value = loaded;
                    loadedAt = Instant.now();
                    log.info("Dashboard section '{}' loaded", name);
                    return loaded;
                } catch (Exception ex) {
                    String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    log.warn("Dashboard section '{}' could not be loaded: {}", name, reason);
                    Map<String, Object> previous = value;
                    if (previous != null) {
                        Map<String, Object> stale = new LinkedHashMap<>(previous);
                        stale.put("stale", true);
                        stale.put("message", "Showing the last successful load. Refresh failed: " + reason);
                        loadedAt = Instant.now().minus(ttl).plusSeconds(15);   // retry in about 15 seconds
                        value = stale;
                        return stale;
                    }
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("status", "ERROR");
                    error.put("message", reason);
                    error.put("fetchedAt", Instant.now().toString());
                    return error;
                }
            }
        }

        private boolean fresh() {
            Instant at = loadedAt;
            return at != null && Duration.between(at, Instant.now()).compareTo(ttl) < 0;
        }
    }
}
