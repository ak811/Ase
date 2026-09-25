package io.github.ak811.ase.server;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Server settings from command-line flags, falling back to environment variables
 * ({@code --index} ↔ {@code ASE_INDEX}, {@code --port} ↔ {@code ASE_PORT} or {@code PORT}, …).
 */
record ServerConfig(Path index, String host, int port, int threads, String siteName, int rateLimitPerMinute,
                    boolean trustProxy, int reloadSeconds, int cacheMb) {

    static final String DEFAULT_HOST = "127.0.0.1";
    static final int DEFAULT_PORT = 8080;
    static final String DEFAULT_SITE_NAME = "Ase";
    static final int DEFAULT_RATE_LIMIT = 120;
    static final int DEFAULT_RELOAD_SECONDS = 10;
    static final int DEFAULT_CACHE_MB = 64;

    ServerConfig {
        if (index == null) {
            throw new IllegalArgumentException("--index is required (or set ASE_INDEX)");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("--port must be between 0 and 65535");
        }
        if (threads < 1 || threads > 1024) {
            throw new IllegalArgumentException("--threads must be between 1 and 1024");
        }
        if (siteName == null || siteName.isBlank() || siteName.length() > 60) {
            throw new IllegalArgumentException("--site-name must be 1-60 characters");
        }
        if (rateLimitPerMinute < 0) {
            throw new IllegalArgumentException("--rate-limit must be >= 0 (0 disables it)");
        }
        if (reloadSeconds < 0) {
            throw new IllegalArgumentException("--reload-seconds must be >= 0 (0 disables it)");
        }
        if (cacheMb < 0) {
            throw new IllegalArgumentException("--cache-mb must be >= 0");
        }
    }

    static ServerConfig parse(String[] args, Map<String, String> env) {
        String index = env.get("ASE_INDEX");
        String host = env.getOrDefault("ASE_HOST", DEFAULT_HOST);
        String port = env.getOrDefault("ASE_PORT", env.getOrDefault("PORT", String.valueOf(DEFAULT_PORT)));
        String threads = env.getOrDefault("ASE_THREADS",
                String.valueOf(Math.max(4, Runtime.getRuntime().availableProcessors() * 2)));
        String siteName = env.getOrDefault("ASE_SITE_NAME", DEFAULT_SITE_NAME);
        String rateLimit = env.getOrDefault("ASE_RATE_LIMIT", String.valueOf(DEFAULT_RATE_LIMIT));
        boolean trustProxy = Boolean.parseBoolean(env.getOrDefault("ASE_TRUST_PROXY", "false"));
        String reload = env.getOrDefault("ASE_RELOAD_SECONDS", String.valueOf(DEFAULT_RELOAD_SECONDS));
        String cache = env.getOrDefault("ASE_CACHE_MB", String.valueOf(DEFAULT_CACHE_MB));

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String value = null;
            if (arg.startsWith("--") && arg.contains("=")) {
                value = arg.substring(arg.indexOf('=') + 1);
                arg = arg.substring(0, arg.indexOf('='));
            }
            if (arg.equals("--trust-proxy")) {
                trustProxy = value == null || Boolean.parseBoolean(value);
                continue;
            }
            if (value == null) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(arg + " requires a value");
                }
                value = args[++i];
            }
            switch (arg) {
                case "--index":
                    index = value;
                    break;
                case "--host":
                    host = value;
                    break;
                case "--port":
                    port = value;
                    break;
                case "--threads":
                    threads = value;
                    break;
                case "--site-name":
                    siteName = value;
                    break;
                case "--rate-limit":
                    rateLimit = value;
                    break;
                case "--reload-seconds":
                    reload = value;
                    break;
                case "--cache-mb":
                    cache = value;
                    break;
                default:
                    throw new IllegalArgumentException("unknown option: " + arg);
            }
        }
        return new ServerConfig(index == null || index.isBlank() ? null : Path.of(index), host,
                integer(port, "--port"), integer(threads, "--threads"), siteName.strip(),
                integer(rateLimit, "--rate-limit"), trustProxy, integer(reload, "--reload-seconds"),
                integer(cache, "--cache-mb"));
    }

    private static int integer(String value, String option) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " expects an integer, got '" + value + "'");
        }
    }

    static String usage() {
        return String.join(System.lineSeparator(),
                "Usage: ase-server --index <index.idx> [options]",
                "",
                "Serves the search page and JSON API for an index built by ase-indexer.",
                "Every option can also be set with an environment variable (shown in brackets).",
                "",
                "  --index <file>          index to serve                                   [ASE_INDEX]",
                "  --host <address>        interface to bind (default " + DEFAULT_HOST + ")               [ASE_HOST]",
                "  --port <n>              port (default " + DEFAULT_PORT + ", 0 = any free port)        [ASE_PORT, PORT]",
                "  --threads <n>           request threads (default 2 × CPUs, at least 4)   [ASE_THREADS]",
                "  --site-name <text>      name shown in the page (default " + DEFAULT_SITE_NAME + ")          [ASE_SITE_NAME]",
                "  --rate-limit <n>        API requests per minute per client; 0 = off (default "
                        + DEFAULT_RATE_LIMIT + ") [ASE_RATE_LIMIT]",
                "  --trust-proxy           take the client address from X-Forwarded-For    [ASE_TRUST_PROXY]",
                "  --reload-seconds <n>    check the index file for changes; 0 = off (default "
                        + DEFAULT_RELOAD_SECONDS + ") [ASE_RELOAD_SECONDS]",
                "  --cache-mb <n>          postings cache size (default " + DEFAULT_CACHE_MB + ")                [ASE_CACHE_MB]",
                "  -h, --help              show this help");
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "index=%s host=%s port=%d threads=%d rateLimit=%d/min reload=%ds cache=%dMB",
                index, host, port, threads, rateLimitPerMinute, reloadSeconds, cacheMb);
    }
}
