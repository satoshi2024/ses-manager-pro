package com.ses.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * REV-ECS-P2-003: SIGTERM 時に WebServer.shutDownGracefully を明示呼び出し、
 * 完了ログと uploads 証拠を残す（Docker Desktop の stdout 欠落対策を含む）。
 */
@Component
public class GracefulShutdownModeReporter implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownModeReporter.class);
    private static final Path OPS_DIR = Path.of("/app/uploads/.ops");

    private final Environment environment;
    private final ApplicationContext applicationContext;
    private final ObjectProvider<ServerProperties> serverProperties;
    private final ObjectProvider<WebServerGracefulShutdownLifecycle> bootGracefulLifecycle;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public GracefulShutdownModeReporter(
            Environment environment,
            ApplicationContext applicationContext,
            ObjectProvider<ServerProperties> serverProperties,
            ObjectProvider<WebServerGracefulShutdownLifecycle> bootGracefulLifecycle) {
        this.environment = environment;
        this.applicationContext = applicationContext;
        this.serverProperties = serverProperties;
        this.bootGracefulLifecycle = bootGracefulLifecycle;
    }

    @PostConstruct
    void report() {
        String mode = environment.getProperty("server.shutdown", "immediate");
        String timeout = environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase", "(unset)");
        ServerProperties props = serverProperties.getIfAvailable();
        String propsMode = props != null && props.getShutdown() != null ? props.getShutdown().name() : "(no ServerProperties)";
        boolean lifecyclePresent = bootGracefulLifecycle.getIfAvailable() != null;
        log.info(
                "server.shutdown effective value={} lifecycle.timeout-per-shutdown-phase={} ServerProperties.shutdown={} WebServerGracefulShutdownLifecycle={}",
                mode,
                timeout,
                propsMode,
                lifecyclePresent);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> appendEvidence(
                "jvm-shutdown-hook.txt",
                "jvm-shutdown-hook ran at " + Instant.now() + " server.shutdown=" + mode + System.lineSeparator()),
                "ses-jvm-shutdown-evidence"));
    }

    @Override
    public void start() {
        running.set(true);
    }

    @Override
    public void stop() {
        stop(() -> { });
    }

    @Override
    public void stop(Runnable callback) {
        try {
            doGracefulStop();
        } finally {
            running.set(false);
            callback.run();
        }
    }

    private void doGracefulStop() {
        // Same canonical messages as Spring Boot Tomcat GracefulShutdown (gate matches these).
        log.info("Commencing graceful shutdown. Waiting for active requests to complete");
        appendEvidence("graceful-commence.txt", "commence at " + Instant.now() + System.lineSeparator());
        try {
            if (applicationContext instanceof WebServerApplicationContext webCtx) {
                WebServer webServer = webCtx.getWebServer();
                CountDownLatch latch = new CountDownLatch(1);
                webServer.shutDownGracefully(result -> {
                    appendEvidence(
                            "graceful-result.txt",
                            "result=" + result + " at " + Instant.now() + System.lineSeparator());
                    latch.countDown();
                });
                long timeoutSec = parseTimeoutSeconds(
                        environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase", "30s"));
                if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
                    log.warn("graceful shutdown timed out after {}s", timeoutSec);
                }
            } else {
                log.warn("ApplicationContext is not WebServerApplicationContext — skip WebServer drain");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("graceful shutdown interrupted");
        } catch (Exception ex) {
            log.warn("graceful shutdown drain error: {}", ex.toString());
        }
        log.info("Graceful shutdown complete");
        appendEvidence("graceful-complete.txt", "complete at " + Instant.now() + System.lineSeparator());
        appendEvidence("context-closed.txt", "SmartLifecycle stop at " + Instant.now() + System.lineSeparator());
    }

    private static long parseTimeoutSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return 30L;
        }
        String t = raw.trim().toLowerCase();
        try {
            if (t.endsWith("ms")) {
                return Math.max(1L, Long.parseLong(t.replace("ms", "").trim()) / 1000L);
            }
            if (t.endsWith("s")) {
                return Math.max(1L, Long.parseLong(t.replace("s", "").trim()));
            }
            return Math.max(1L, Long.parseLong(t));
        } catch (NumberFormatException ex) {
            return 30L;
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Stop before Boot's WebServerGracefulShutdownLifecycle (PHASE = MAX-1024) so our
     * commence/complete evidence is always emitted on SIGTERM-driven context close.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    private static void appendEvidence(String fileName, String line) {
        try {
            Files.createDirectories(OPS_DIR);
            Files.writeString(
                    OPS_DIR.resolve(fileName),
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (Exception ex) {
            System.err.println("shutdown evidence write failed: " + ex);
        }
    }
}
