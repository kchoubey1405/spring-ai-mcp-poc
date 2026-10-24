package com.schneider.ai.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** Runs an external command without a shell, with a hard timeout, capturing a bounded amount of output. */
final class ProcessRunner {

    private static final int TAIL_LINES = 400;
    private static final int MATCH_LINES = 200;

    record Result(int exitCode, boolean timedOut, long millis, List<String> tail, List<String> matches) {
        boolean success() {
            return !timedOut && exitCode == 0;
        }

        String tailText(int maxLines, int maxChars) {
            int from = Math.max(0, tail.size() - maxLines);
            String text = String.join("\n", tail.subList(from, tail.size()));
            return text.length() <= maxChars ? text : "..." + text.substring(text.length() - maxChars);
        }
    }

    private ProcessRunner() {
    }

    /**
     * @param matchPattern lines matching this pattern are collected separately (for example Maven [ERROR] lines)
     * @param logFile      full output is written here (may be null)
     * @param scrub        applied to every output line before it is stored (to mask secrets)
     */
    static Result run(List<String> command, Path cwd, Map<String, String> extraEnv, Duration timeout,
                      Pattern matchPattern, Path logFile, UnaryOperator<String> scrub) throws IOException, InterruptedException {
        return run(command, cwd, extraEnv, timeout, matchPattern, logFile, scrub, null, null);
    }

    /**
     * @param earlyExitSuccessPattern if matched in output, process is cleanly stopped and considered successful (e.g. spring-boot:run startup)
     * @param earlyExitFailurePattern if matched in output, process is cleanly stopped and considered failed
     */
    static Result run(List<String> command, Path cwd, Map<String, String> extraEnv, Duration timeout,
                      Pattern matchPattern, Path logFile, UnaryOperator<String> scrub,
                      Pattern earlyExitSuccessPattern, Pattern earlyExitFailurePattern) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        builder.environment().putAll(extraEnv);
        long start = System.currentTimeMillis();
        Process process = builder.start();
        process.getOutputStream().close();

        Deque<String> tail = new ArrayDeque<>();
        List<String> matches = new ArrayList<>();
        AtomicBoolean earlySuccess = new AtomicBoolean(false);
        AtomicBoolean earlyFailure = new AtomicBoolean(false);

        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter log = logFile == null ? null : Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = in.readLine()) != null) {
                    String clean = scrub.apply(line);
                    synchronized (tail) {
                        tail.addLast(clean);
                        if (tail.size() > TAIL_LINES) {
                            tail.removeFirst();
                        }
                        if (matchPattern != null && matches.size() < MATCH_LINES && matchPattern.matcher(clean).find()) {
                            matches.add(clean);
                        }
                    }
                    if (log != null) {
                        log.write(clean);
                        log.newLine();
                        log.flush();
                    }
                    if (earlyExitSuccessPattern != null && earlyExitSuccessPattern.matcher(clean).find()) {
                        earlySuccess.set(true);
                        break;
                    }
                    if (earlyExitFailurePattern != null && earlyExitFailurePattern.matcher(clean).find()) {
                        earlyFailure.set(true);
                        break;
                    }
                }
            } catch (IOException ignored) {
                // process ended / stream closed
            }
        }, "process-reader");
        reader.setDaemon(true);
        reader.start();

        long deadline = System.currentTimeMillis() + timeout.toMillis();
        boolean finished = false;
        while (System.currentTimeMillis() < deadline) {
            if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                finished = true;
                break;
            }
            if (earlySuccess.get() || earlyFailure.get()) {
                Thread.sleep(800);
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                finished = true;
                break;
            }
        }

        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        reader.join(5000);
        int exitCode;
        if (earlySuccess.get()) {
            exitCode = 0;
        } else if (earlyFailure.get()) {
            exitCode = 1;
        } else {
            exitCode = finished ? process.exitValue() : -1;
        }
        boolean timedOut = !finished && !earlySuccess.get() && !earlyFailure.get();
        synchronized (tail) {
            return new Result(exitCode, timedOut, System.currentTimeMillis() - start,
                    new ArrayList<>(tail), new ArrayList<>(matches));
        }
    }
}
