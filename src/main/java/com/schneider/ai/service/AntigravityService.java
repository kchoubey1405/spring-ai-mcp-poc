package com.schneider.ai.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Antigravity CLI LLM provider.
 * Integrates Google Antigravity (Gemini / Claude / GPT models via agy CLI) into the agent orchestration.
 */
@Slf4j
@Service("antigravityCliService")
@ConditionalOnProperty(name = "antigravity.cli.enabled", havingValue = "true", matchIfMissing = false)
public class AntigravityService implements LlmProviderService {

    private final boolean enabled;
    private final String command;
    private final String model;
    private final int timeoutSeconds;
    private final java.util.Map<String, Process> activeProcesses = new java.util.concurrent.ConcurrentHashMap<>();

    private volatile boolean available;

    public AntigravityService(
            @Value("${antigravity.cli.enabled:false}") boolean enabled,
            @Value("${antigravity.cli.command:agy}") String command,
            @Value("${antigravity.cli.model:gemini-3.8-flash-high}") String model,
            @Value("${antigravity.cli.timeout-seconds:480}") int timeoutSeconds) {
        this.enabled = enabled;
        this.command = command;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 480;
    }

    @PostConstruct
    public void initialize() {
        if (!enabled) {
            log.info("Antigravity CLI provider is disabled (antigravity.cli.enabled=false)");
            this.available = false;
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(command, "--help");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                this.available = true;
                log.info("Antigravity CLI provider initialized successfully with command [{}] and model [{}]",
                        command, model);
            } else {
                this.available = false;
                log.warn("Antigravity CLI command [{}] check timed out or exited with non-zero code", command);
            }
        } catch (Exception ex) {
            this.available = false;
            log.warn("Antigravity CLI command [{}] is not available on this machine: {}", command, ex.getMessage());
        }
    }

    @Override
    public String getProviderName() {
        return "antigravity-cli";
    }

    @Override
    public String getDisplayName() {
        return "Antigravity (Gemini)";
    }

    @Override
    public boolean isAvailable() {
        return enabled && available;
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public String sendAndWait(String appSessionId, String prompt, List<ToolCallback> tools) throws Exception {
        return sendAndStream(appSessionId, prompt, tools, null);
    }

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    public String sendAndStream(String appSessionId, String prompt, List<ToolCallback> tools, java.util.function.Consumer<String> chunkConsumer) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("Antigravity provider is not available. Ensure antigravity.cli.enabled=true and '"
                    + command + "' is installed and authenticated on this machine.");
        }

        String effectivePrompt = (tools != null && !tools.isEmpty())
                ? LlmPromptUtils.buildPromptWithToolContext(prompt, tools)
                : prompt;

        String systemDirective = "IMPORTANT: You are an LLM completion engine in an automated agent orchestration system. " +
                "Do NOT use any built-in tools (do not view files, run commands, or search). " +
                "Respond with plain text only. If you need to invoke an available enterprise tool, output exactly this format on its own line: TOOL_CALL: tool_name(parameters)\n\n";

        String promptWithDirective = systemDirective + effectivePrompt;

        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.add("--input-format=stream-json");
        cmd.add("--output-format=stream-json");
        cmd.add("--disable-slash-commands");
        cmd.add("--print-timeout=0");
        cmd.add("--dangerously-skip-permissions");
        if (model != null && !model.isBlank()) {
            cmd.add("--model=" + model);
        }

        log.debug("Session [{}]: Executing Antigravity CLI command with model [{}] via stream-json", appSessionId, model);

        int effectiveTimeout = (tools != null && !tools.isEmpty())
                ? Math.max(timeoutSeconds, 480)
                : timeoutSeconds;

        java.nio.file.Path tempWorkDir = java.nio.file.Files.createTempDirectory("agy-work-");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(tempWorkDir.toFile());

        long start = System.currentTimeMillis();
        Process process = pb.start();
        if (appSessionId != null) {
            activeProcesses.put(appSessionId, process);
        }

        try {
            StringBuilder accumulatedText = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            java.util.concurrent.atomic.AtomicReference<String> finalResponse = new java.util.concurrent.atomic.AtomicReference<>(null);
            java.util.concurrent.atomic.AtomicReference<String> streamError = new java.util.concurrent.atomic.AtomicReference<>(null);
            java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(1);

            Thread outReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) {
                            continue;
                        }
                        if (!line.startsWith("{")) {
                            continue;
                        }
                        try {
                            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(line);
                            String event = root.path("event").asText("");
                            if ("step_update".equals(event)) {
                                com.fasterxml.jackson.databind.JsonNode stepUpdate = root.path("step_update");
                                String textDelta = stepUpdate.path("text_delta").asText("");
                                if (!textDelta.isEmpty()) {
                                    accumulatedText.append(textDelta);
                                    if (chunkConsumer != null) {
                                        chunkConsumer.accept(textDelta);
                                    }
                                }
                            } else if ("result".equals(event)) {
                                com.fasterxml.jackson.databind.JsonNode resultNode = root.path("result");
                                String response = resultNode.path("response").asText("");
                                if (!response.isEmpty()) {
                                    finalResponse.set(response);
                                }
                                String status = resultNode.path("status").asText("");
                                if ("ERROR".equalsIgnoreCase(status)) {
                                    String err = resultNode.path("error").asText("");
                                    streamError.set(err);
                                }
                                doneLatch.countDown();
                            }
                        } catch (Exception parseEx) {
                            log.debug("Session [{}]: Non-JSON or unparseable line from agy: {}", appSessionId, line);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }, "antigravity-stdout");

            Thread errReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (stderr.length() > 0) {
                            stderr.append("\n");
                        }
                        stderr.append(line);
                    }
                } catch (Exception ignored) {}
            }, "antigravity-stderr");

            outReader.setDaemon(true);
            errReader.setDaemon(true);
            outReader.start();
            errReader.start();

            Thread inWriter = new Thread(() -> {
                try (java.io.OutputStream os = process.getOutputStream()) {
                    Map<String, Object> inputMsg = Map.of(
                            "event", "user",
                            "message", Map.of("content", promptWithDirective)
                    );
                    String jsonLine = objectMapper.writeValueAsString(inputMsg) + "\n";
                    os.write(jsonLine.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (Exception ex) {
                    log.warn("Session [{}]: Error writing prompt to Antigravity CLI stdin: {}", appSessionId, ex.getMessage());
                }
            }, "antigravity-stdin");
            inWriter.setDaemon(true);
            inWriter.start();

            boolean turnCompleted = doneLatch.await(effectiveTimeout, TimeUnit.SECONDS);
            if (!turnCompleted) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                long elapsed = (System.currentTimeMillis() - start) / 1000;
                throw new RuntimeException("Antigravity CLI timed out after " + elapsed + " seconds. Complex code generation may need more time.");
            }

            boolean processExited = process.waitFor(5, TimeUnit.SECONDS);
            if (!processExited) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }

            outReader.join(1000);
            errReader.join(1000);

            if (streamError.get() != null) {
                String err = streamError.get();
                log.error("Antigravity CLI reported stream error: {}", err);
                throw new RuntimeException("Antigravity CLI reported error: " + err);
            }

            String result = finalResponse.get();
            if (result != null && !result.isBlank()) {
                return result.trim();
            }
            if (accumulatedText.length() > 0) {
                return accumulatedText.toString().trim();
            }

            if (processExited && process.exitValue() != 0) {
                String err = stderr.toString().trim();
                log.error("Antigravity CLI failed (exit code {}): {}", process.exitValue(), err);
                throw new RuntimeException("Antigravity CLI failed with exit code " + process.exitValue() + ": " + err);
            }

            return "";
        } finally {
            try {
                process.getOutputStream().close();
            } catch (Exception ignored) {}
            try {
                java.nio.file.Files.walk(tempWorkDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .map(java.nio.file.Path::toFile)
                        .forEach(java.io.File::delete);
            } catch (Exception ignored) {}
            if (appSessionId != null) {
                activeProcesses.remove(appSessionId);
            }
        }
    }

    @Override
    public String sendAndWait(String appSessionId, String prompt) throws Exception {
        return sendAndWait(appSessionId, prompt, Collections.emptyList());
    }

    @Override
    public void deleteSession(String appSessionId) {
        if (appSessionId != null) {
            Process process = activeProcesses.remove(appSessionId);
            if (process != null && process.isAlive()) {
                log.info("Session [{}]: Forcibly terminating active Antigravity CLI process", appSessionId);
                try {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                } catch (Exception ex) {
                    log.warn("Session [{}]: Error terminating Antigravity process: {}", appSessionId, ex.getMessage());
                }
            }
        }
    }
}
