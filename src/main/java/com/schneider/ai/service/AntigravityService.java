package com.schneider.ai.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Antigravity CLI LLM provider.
 * Integrates Google Antigravity (Gemini / Claude / GPT models via agy CLI) into the agent orchestration.
 */
@Slf4j
@Service
public class AntigravityService implements LlmProviderService {

    private final boolean enabled;
    private final String command;
    private final String model;
    private final int timeoutSeconds;
    private final java.util.Map<String, Process> activeProcesses = new java.util.concurrent.ConcurrentHashMap<>();

    private volatile boolean available;

    public AntigravityService(
            @Value("${antigravity.cli.enabled:true}") boolean enabled,
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
        return "antigravity";
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

    @Override
    public String sendAndStream(String appSessionId, String prompt, List<ToolCallback> tools, java.util.function.Consumer<String> chunkConsumer) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("Antigravity provider is not available. Ensure antigravity.cli.enabled=true and '"
                    + command + "' is installed and authenticated on this machine.");
        }

        String effectivePrompt = (tools != null && !tools.isEmpty())
                ? LlmPromptUtils.buildPromptWithToolContext(prompt, tools)
                : prompt;

        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.add("--output-format=text");
        cmd.add("--disable-slash-commands");
        if (model != null && !model.isBlank()) {
            cmd.add("--model=" + model);
        }

        log.debug("Session [{}]: Executing Antigravity CLI command with model [{}] via standard input", appSessionId, model);

        int effectiveTimeout = (tools != null && !tools.isEmpty())
                ? Math.max(timeoutSeconds, 480)
                : timeoutSeconds;

        ProcessBuilder pb = new ProcessBuilder(cmd);
        long start = System.currentTimeMillis();
        Process process = pb.start();
        if (appSessionId != null) {
            activeProcesses.put(appSessionId, process);
        }

        try {
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread outReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (stdout.length() > 0) {
                            stdout.append("\n");
                        }
                        stdout.append(line);
                        if (chunkConsumer != null) {
                            chunkConsumer.accept(line + "\n");
                        }
                    }
                } catch (Exception ignored) {}
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
                    os.write(effectivePrompt.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (Exception ex) {
                    log.warn("Session [{}]: Error writing prompt to Antigravity CLI stdin: {}", appSessionId, ex.getMessage());
                }
            }, "antigravity-stdin");
            inWriter.setDaemon(true);
            inWriter.start();

            boolean completed = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);
            if (!completed) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                long elapsed = (System.currentTimeMillis() - start) / 1000;
                throw new RuntimeException("Antigravity CLI timed out after " + elapsed + " seconds. Complex code generation may need more time.");
            }

            outReader.join(2000);
            errReader.join(2000);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String err = stderr.toString().trim();
                log.error("Antigravity CLI failed (exit code {}): {}", exitCode, err);
                throw new RuntimeException("Antigravity CLI failed with exit code " + exitCode + ": " + err);
            }

            if (stderr.length() > 0) {
                log.debug("Antigravity CLI stderr: {}", stderr);
            }

            return stdout.toString().trim();
        } finally {
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
