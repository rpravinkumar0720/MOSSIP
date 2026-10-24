package io.mosip.liveness.server;

/**
 * Server configuration loaded from environment variables or command-line arguments.
 */
public record ServerConfig(
        int port,
        boolean diagnosticMode,
        String modelPath,
        String auditFilePath,
        int lockoutSeconds,
        boolean demoMode
) {
    public static ServerConfig fromEnvAndArgs(String[] args) {
        int port = getEnvInt("PORT", 8080);
        boolean diagnostic = getEnvBool("LIVENESS_DIAGNOSTIC", false);
        String modelPath = System.getenv("LIVENESS_MODEL_PATH");
        String auditFile = System.getenv("LIVENESS_AUDIT_FILE");
        int lockout = getEnvInt("LIVENESS_LOCKOUT_SECONDS", 60);
        boolean demo = getEnvBool("LIVENESS_DEMO", true);

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
                }
            }
        }

        return new ServerConfig(port, diagnostic, modelPath, auditFile, lockout, demo);
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

    private static boolean getEnvBool(String key, boolean def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return def;
        return Boolean.parseBoolean(v.trim());
    }
}
