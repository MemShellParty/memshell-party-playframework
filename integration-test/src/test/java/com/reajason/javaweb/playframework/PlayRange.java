package com.reajason.javaweb.playframework;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One Play range JVM. The range is a real Play server, started outside the test JVM so the
 * injection runs against a pristine Akka/Pekko stack.
 */
final class PlayRange implements Closeable {

    private static final Pattern LISTENING = Pattern.compile("RANGE_LISTENING (\\d+)");

    private final Process process;
    private final List<String> output;
    private final int port;

    private PlayRange(Process process, int port, List<String> output) {
        this.process = process;
        this.port = port;
        this.output = output;
    }

    static PlayRange start(String classpath) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
                "-Drange.port=0",
                "-cp", classpath,
                "com.reajason.javaweb.playframework.range.PlayframeworkRangeApplication");
        builder.redirectErrorStream(true);
        Process process = builder.start();

        List<String> output = Collections.synchronizedList(new ArrayList<String>());
        AtomicInteger port = new AtomicInteger(-1);
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    output.add(line);
                    Matcher matcher = LISTENING.matcher(line);
                    if (matcher.find()) {
                        port.set(Integer.parseInt(matcher.group(1)));
                    }
                }
            } catch (IOException ignored) {
                // the process exiting closes the stream
            }
        }, "play-range-output");
        reader.setDaemon(true);
        reader.start();

        long deadline = System.currentTimeMillis() + 60000;
        while (port.get() < 0 && System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("range exited before listening:\n" + String.join("\n", output));
            }
            Thread.sleep(100);
        }
        if (port.get() < 0) {
            process.destroyForcibly();
            throw new IllegalStateException("range did not start in 60s:\n" + String.join("\n", output));
        }
        return new PlayRange(process, port.get(), output);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    String output() {
        synchronized (output) {
            return String.join("\n", output);
        }
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
