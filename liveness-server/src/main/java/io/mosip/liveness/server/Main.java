package io.mosip.liveness.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the MOSIP Liveness & PAD Server.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        ServerConfig config = ServerConfig.fromEnvAndArgs(args);

        log.info("=================================================================");
        log.info("Starting MOSIP Face Liveness & PAD Service");
        log.info("Port:                {}", config.port());
        log.info("Diagnostic Mode:     {}", config.diagnosticMode());
        log.info("Demo Mode:           {}", config.demoMode());
        log.info("Lockout Seconds:     {}", config.lockoutSeconds());
        log.info("ONNX Model Path:     {}", config.modelPath() != null ? config.modelPath() : "None (pure heuristic baseline)");
        log.info("Audit File Path:     {}", config.auditFilePath() != null ? config.auditFilePath() : "In-memory only");
        log.info("=================================================================");

        LivenessServerApp serverApp = new LivenessServerApp(config);
        serverApp.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down MOSIP Face Liveness & PAD Service...");
            serverApp.stop();
            log.info("Shutdown completed.");
        }));
    }
}
