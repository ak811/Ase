package io.github.ak811.ase.server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** HTTP server for the search page and API. Uses only the JDK. */
public final class WebServer implements AutoCloseable {
    static final String VERSION = "2.0.0";
    private static final Logger LOG = Logger.getLogger(WebServer.class.getName());
    /** Held strongly so its level is not lost to garbage collection. */
    private static final Logger APP_LOGGER = Logger.getLogger("io.github.ak811.ase");

    private final HttpServer server;
    private final ExecutorService executor;
    private final SearchService service;
    private final CountDownLatch stopped = new CountDownLatch(1);

    WebServer(ServerConfig config) throws IOException {
        this.service = new SearchService(config.index(), config.cacheMb() * 1024L * 1024L, config.reloadSeconds());
        try {
            server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 256);
            AtomicInteger threadNumber = new AtomicInteger();
            executor = Executors.newFixedThreadPool(config.threads(), runnable -> {
                Thread thread = new Thread(runnable, "http-" + threadNumber.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            RateLimiter rateLimiter = new RateLimiter(config.rateLimitPerMinute());
            server.createContext("/api/", new ApiHandler(service, rateLimiter, config, VERSION));
            server.createContext("/healthz", exchange -> {
                try {
                    HttpSupport.sendJson(exchange, 200,
                            Map.of("status", "ok", "documents", service.engine().reader().documentCount()));
                } finally {
                    exchange.close();
                }
            });
            server.createContext("/", new StaticHandler(config.siteName()));
        } catch (IOException | RuntimeException e) {
            service.close();
            throw e;
        }
    }

    void start() {
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    SearchService service() {
        return service;
    }

    /** Blocks until {@link #close()} has run (e.g. from the shutdown hook). */
    void awaitShutdown() throws InterruptedException {
        stopped.await();
    }

    @Override
    public void close() {
        if (stopped.getCount() == 0) {
            return;
        }
        server.stop(2);
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        service.close();
        stopped.countDown();
    }

    public static void main(String[] args) {
        int code = run(args, System.getenv(), System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        for (String arg : args) {
            if (arg.equals("-h") || arg.equals("--help")) {
                out.println(ServerConfig.usage());
                return 0;
            }
        }
        ServerConfig config;
        try {
            config = ServerConfig.parse(args, env);
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.println();
            err.println(ServerConfig.usage());
            return 2;
        }
        configureLogging();
        // Bound slow clients; must be set before the HTTP server classes are initialized.
        System.setProperty("sun.net.httpserver.maxReqTime", System.getProperty("sun.net.httpserver.maxReqTime", "30"));
        System.setProperty("sun.net.httpserver.maxRspTime", System.getProperty("sun.net.httpserver.maxRspTime", "60"));

        WebServer server;
        try {
            server = new WebServer(config);
        } catch (IOException e) {
            err.println("error: cannot start: " + e.getMessage());
            return 1;
        }
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // java.util.logging resets its handlers in its own shutdown hook, so write directly.
            err.println(logLine("INFO", "Shutting down"));
            server.close();
            err.println(logLine("INFO", "Stopped"));
            err.flush();
        }, "shutdown"));
        LOG.info(String.format(Locale.ROOT, "Serving %,d documents on http://%s:%d/ (%s)",
                server.service.engine().reader().documentCount(), displayHost(config.host()), server.port(), config));
        try {
            server.awaitShutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    static String logLine(String level, String message) {
        return String.format(Locale.ROOT, "%1$tFT%1$tT %2$-7s %3$s", System.currentTimeMillis(), level, message);
    }

    private static String displayHost(String host) {
        return host.equals("0.0.0.0") || host.equals("::") ? "localhost" : host;
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        for (var handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord record) {
                String message = String.format(Locale.ROOT, "%1$tFT%1$tT %2$-7s %3$s%n",
                        record.getMillis(), record.getLevel().getName(), formatMessage(record));
                if (record.getThrown() != null) {
                    java.io.StringWriter trace = new java.io.StringWriter();
                    record.getThrown().printStackTrace(new java.io.PrintWriter(trace));
                    message += trace;
                }
                return message;
            }
        });
        handler.setLevel(Level.ALL);
        root.addHandler(handler);
        root.setLevel(Level.INFO);
        // ASE_LOG_LEVEL applies to this application's loggers only, not to the JDK's internals.
        String level = System.getenv().getOrDefault("ASE_LOG_LEVEL", "INFO");
        try {
            APP_LOGGER.setLevel(Level.parse(level.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            APP_LOGGER.setLevel(Level.INFO);
        }
    }
}
