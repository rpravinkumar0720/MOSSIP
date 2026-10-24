package io.mosip.liveness.server;

import java.util.Arrays;
import java.util.List;

/**
 * Server configuration loaded from environment variables or command-line arguments.
 */
public record ServerConfig(
        int port,
        boolean diagnosticMode,
        String modelPath,
        String auditFilePath,
        int lockoutSeconds,
        boolean demoMode,
        String adminToken,
        String sessionSecret,
        List<String> allowedOrigins,
        int maxConnectionsPerIp,
        long maxSessionBytes
) {
    public ServerConfig(int port, boolean diagnosticMode, String modelPath, String auditFilePath, int lockoutSeconds, boolean demoMode) {
        this(port, diagnosticMode, modelPath, auditFilePath, lockoutSeconds, demoMode,
                "mosip-admin-token-2026", "mosip-session-secret-key-32bytes-hex", List.of("*"), 10, 15 * 1024 * 1024L);
    }

    public static ServerConfig fromEnvAndArgs(String[] args) {
        int port = getEnvInt("PORT", 8080);
        boolean diagnostic = getEnvBool("LIVENESS_DIAGNOSTIC", false);
        String modelPath = System.getenv("LIVENESS_MODEL_PATH");
        String auditFile = System.getenv("LIVENESS_AUDIT_FILE");
        int lockout = getEnvInt("LIVENESS_LOCKOUT_SECONDS", 60);
        boolean demo = getEnvBool("LIVENESS_DEMO", false); // P0-3: demo defaults to false
        String adminToken = getEnvString("LIVENESS_ADMIN_TOKEN", "mosip-admin-token-2026");
        String sessionSecret = getEnvString("LIVENESS_SESSION_SECRET", "mosip-session-secret-key-32bytes-hex");
        String originsStr = getEnvString("LIVENESS_ALLOWED_ORIGINS", "*");
        List<String> allowedOrigins = Arrays.stream(originsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        int maxConnPerIp = getEnvInt("LIVENESS_MAX_CONN_PER_IP", 10);
        long maxBytes = getEnvLong("LIVENESS_MAX_SESSION_BYTES", 15 * 1024 * 1024L);

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--diagnostic".equalsIgnoreCase(arg)) {
                    diagnostic = true;
                } else if ("--demo".equalsIgnoreCase(arg)) {
                    demo = true;
                } else if ("--port".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    port = Integer.parseInt(args[++i]);
                } else if ("--model".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    modelPath = args[++i];
                } else if ("--audit-file".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    auditFile = args[++i];
                } else if ("--lockout".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    lockout = Integer.parseInt(args[++i]);
                } else if ("--admin-token".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    adminToken = args[++i];
                }
            }
        }

        return new ServerConfig(port, diagnostic, modelPath, auditFile, lockout, demo,
                adminToken, sessionSecret, allowedOrigins, maxConnPerIp, maxBytes);
    }

    private static String getEnvString(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static int getEnvInt(String key, int def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long getEnvLong(String key, long def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean getEnvBool(String key, boolean def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return def;
        return Boolean.parseBoolean(v.trim());
    }
}
