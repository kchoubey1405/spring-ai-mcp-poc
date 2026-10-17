package com.schneider.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paging helpers for Jira ticket lists ("next 25", "page 3", "show more").
 * <p>
 * JQL has no OFFSET clause - the Jira search API pages with startAt/maxResults. The orchestrator remembers the last
 * search of every chat session (JQL, startAt, page size) and answers follow-ups by calling the search tool with the
 * same JQL and the next startAt, so a page never depends on what the model makes of "next 25".
 */
final class JiraPaging {

    static final int DEFAULT_PAGE_SIZE = 25;
    static final int MAX_PAGE_SIZE = 100;

    /** Last search of a chat session. */
    record State(String jql, int startAt, int pageSize) {
    }

    private static final Pattern PAGINATION = Pattern.compile(
            "\\bnext\\b\\s*(\\d{1,3}(?![.\\d])|page\\b|set\\b|batch\\b|ones?\\b|tickets?\\b|jiras?\\b|issues?\\b|results?\\b|items?\\b)"
                    + "|\\b(more|additional|another|remaining)\\s+(\\d{1,3}\\s+)?(tickets?|jiras?|issues?|results?|items?|page|ones?)\\b"
                    + "|\\brest of (the )?(tickets?|jiras?|issues?|results?|list)\\b"
                    + "|\\b(show|give me|load|get|fetch)\\s+more\\b"
                    + "|\\bpage\\s*\\d{1,3}\\b"
                    + "|\\b(second|third|fourth|fifth|2nd|3rd|4th|5th)\\s+page\\b"
                    + "|\\b(previous|prior|last)\\s+page\\b"
                    + "|^\\s*(continue|more|next|next page)\\W*$");
    private static final Pattern PREVIOUS_PAGE = Pattern.compile("\\b(previous|prior|last|back)\\s+page\\b|\\bprevious\\s+\\d{1,3}\\b");
    private static final Pattern PAGE_NUMBER = Pattern.compile("\\bpage\\s*(\\d{1,3})\\b");
    private static final Pattern ORDINAL_PAGE = Pattern.compile("\\b(second|third|fourth|fifth|2nd|3rd|4th|5th)\\s+page\\b");
    private static final Pattern[] SIZE_PATTERNS = {
            Pattern.compile("\\b(?:next|first|top|another|previous)\\s+(\\d{1,3})(?![.\\d])"),
            Pattern.compile("\\b(\\d{1,3})\\s+(?:more|tickets?|jiras?|issues?|results?|items?)\\b"),
            Pattern.compile("\\b(?:list|show|give me|give|fetch|get)\\s+(?:me\\s+)?(?:the\\s+)?(\\d{1,3})(?![.\\d])")
    };

    private JiraPaging() {
    }

    /** "next 25", "next page", "page 3", "show more", "more tickets", plain "next". */
    static boolean isPagination(String prompt) {
        return prompt != null && PAGINATION.matcher(prompt.toLowerCase(Locale.ROOT)).find();
    }

    /** Page size named in the prompt ("next 10", "first 50", "25 more tickets"), bounded to 1..100. */
    static OptionalInt requestedSize(String prompt) {
        if (prompt == null) {
            return OptionalInt.empty();
        }
        String lower = prompt.toLowerCase(Locale.ROOT);
        for (Pattern pattern : SIZE_PATTERNS) {
            Matcher matcher = pattern.matcher(lower);
            if (matcher.find()) {
                int value = Integer.parseInt(matcher.group(1));
                if (value >= 1) {
                    return OptionalInt.of(Math.min(value, MAX_PAGE_SIZE));
                }
            }
        }
        return OptionalInt.empty();
    }

    /** 1-based page number named in the prompt ("page 3", "third page"), or 0 when none. */
    static int requestedPage(String prompt) {
        if (prompt == null) {
            return 0;
        }
        String lower = prompt.toLowerCase(Locale.ROOT);
        Matcher number = PAGE_NUMBER.matcher(lower);
        if (number.find()) {
            return Math.max(1, Integer.parseInt(number.group(1)));
        }
        Matcher ordinal = ORDINAL_PAGE.matcher(lower);
        if (ordinal.find()) {
            return switch (ordinal.group(1)) {
                case "second", "2nd" -> 2;
                case "third", "3rd" -> 3;
                case "fourth", "4th" -> 4;
                default -> 5;
            };
        }
        return 0;
    }

    /** Index of the first ticket of the page the user asks for. */
    static int resolveStart(State previous, String prompt, int size) {
        int page = requestedPage(prompt);
        if (page > 0) {
            return (page - 1) * size;
        }
        String lower = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        if (PREVIOUS_PAGE.matcher(lower).find()) {
            return Math.max(0, previous.startAt() - size);
        }
        return previous.startAt() + previous.pageSize();
    }

    /** Stable order so that consecutive pages do not overlap. */
    static String withStableOrder(String jql) {
        if (jql == null || jql.isBlank()) {
            return jql;
        }
        return jql.toLowerCase(Locale.ROOT).contains("order by") ? jql : jql + " ORDER BY created DESC";
    }

    private static final Pattern OFFSET_CLAUSE = Pattern.compile("(?i)\\s*\\bOFFSET\\s+(\\d+)\\b");

    /**
     * Repairs a search call written by the model: "OFFSET n" is not JQL, it becomes the startAt argument; a missing
     * maxResults becomes the page size (25) and a missing ORDER BY a stable order, so that every page is predictable.
     * Anything that is not a JSON object with a jql is returned unchanged.
     */
    static String sanitizeSearchArguments(ObjectMapper mapper, String json) {
        if (json == null || !json.trim().startsWith("{")) {
            return json;
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (!(node instanceof ObjectNode object) || !object.hasNonNull("jql")) {
                return json;
            }
            String jql = object.get("jql").asText();
            Matcher matcher = OFFSET_CLAUSE.matcher(jql);
            if (matcher.find()) {
                int offset = Integer.parseInt(matcher.group(1));
                jql = matcher.replaceAll("").trim();
                if (!object.has("startAt")) {
                    object.put("startAt", offset);
                }
            }
            object.put("jql", withStableOrder(jql));
            if (!object.hasNonNull("maxResults")) {
                object.put("maxResults", DEFAULT_PAGE_SIZE);
            }
            return mapper.writeValueAsString(object);
        } catch (Exception ex) {
            return json;
        }
    }

    /** State of a (sanitized) search call, or null when it has no jql. */
    static State stateOf(ObjectMapper mapper, String sanitizedJson) {
        try {
            JsonNode node = mapper.readTree(sanitizedJson);
            String jql = node.path("jql").asText("");
            if (jql.isBlank()) {
                return null;
            }
            int size = Math.max(1, Math.min(MAX_PAGE_SIZE, node.path("maxResults").asInt(DEFAULT_PAGE_SIZE)));
            return new State(jql, Math.max(0, node.path("startAt").asInt(0)), size);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Finds the Jira search response (the object with "issues") inside whatever wrapper the MCP layer put around it. */
    static JsonNode findSearchResult(ObjectMapper mapper, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return find(mapper, mapper.readTree(raw), 0);
        } catch (Exception ex) {
            return null;
        }
    }

    private static JsonNode find(ObjectMapper mapper, JsonNode node, int depth) {
        if (node == null || depth > 5) {
            return null;
        }
        if (node.isObject() && node.has("issues")) {
            return node;
        }
        try {
            if (node.isTextual()) {
                String text = node.asText().trim();
                return text.startsWith("{") || text.startsWith("[") ? find(mapper, mapper.readTree(text), depth + 1) : null;
            }
            if (node.isArray()) {
                for (JsonNode element : node) {
                    JsonNode found = find(mapper, element, depth + 1);
                    if (found != null) {
                        return found;
                    }
                }
                return null;
            }
            if (node.isObject()) {
                for (String field : new String[]{"text", "content", "result", "data"}) {
                    if (node.has(field)) {
                        JsonNode found = find(mapper, node.get(field), depth + 1);
                        if (found != null) {
                            return found;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            return null;
        }
        return null;
    }

    /** Markdown answer for one page of tickets. */
    static String render(JsonNode result, int startAt, int pageSize, String jiraBaseUrl) {
        JsonNode issues = result.path("issues");
        int total = result.path("total").asInt(-1);
        int count = issues.isArray() ? issues.size() : 0;
        if (count == 0) {
            return total >= 0
                    ? "There are no more tickets. The search matches " + total + " tickets in total and all of them have been shown."
                    : "There are no more tickets for this search.";
        }
        int from = startAt + 1;
        int to = startAt + count;
        StringBuilder out = new StringBuilder();
        out.append("**Jira tickets ").append(from).append("-").append(to);
        if (total >= 0) {
            out.append(" of ").append(total);
        }
        out.append("**\n\n");
        out.append("| # | Key | Summary | Status | Assignee | Priority | Updated |\n");
        out.append("|---|-----|---------|--------|----------|----------|---------|\n");
        int row = from;
        String base = jiraBaseUrl == null || jiraBaseUrl.isBlank() ? "https://jira.se.com" : jiraBaseUrl.replaceAll("/+$", "");
        for (JsonNode issue : issues) {
            String key = issue.path("key").asText("");
            JsonNode fields = issue.path("fields");
            out.append("| ").append(row++)
                    .append(" | [").append(key).append("](").append(base).append("/browse/").append(key).append(")")
                    .append(" | ").append(cell(fields.path("summary").asText("")))
                    .append(" | ").append(cell(fields.path("status").path("name").asText("")))
                    .append(" | ").append(cell(fields.path("assignee").path("displayName").asText("Unassigned")))
                    .append(" | ").append(cell(fields.path("priority").path("name").asText("")))
                    .append(" | ").append(cell(shortDate(fields.path("updated").asText(""))))
                    .append(" |\n");
        }
        out.append('\n');
        boolean more = total >= 0 ? to < total : count >= pageSize;
        if (more) {
            int nextTo = total >= 0 ? Math.min(to + pageSize, total) : to + pageSize;
            out.append("Say \"next ").append(pageSize).append("\" for tickets ").append(to + 1).append("-").append(nextTo).append(".");
        } else {
            out.append("That is the last page.");
        }
        return out.toString();
    }

    private static String shortDate(String iso) {
        return iso != null && iso.length() >= 10 ? iso.substring(0, 10) : "";
    }

    private static String cell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\r", " ").replace("\n", " ").trim();
    }
}
