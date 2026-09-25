package io.github.ak811.ase.server;

import io.github.ak811.ase.core.index.IndexReader;
import io.github.ak811.ase.core.search.Searcher;
import io.github.ak811.ase.core.search.Suggester;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the open index and swaps in a new one when the file changes on disk. The indexer
 * replaces the file atomically, so a new version is either complete or not visible yet.
 * Requests already using the old index keep it until a grace period after the swap.
 */
final class SearchService implements Closeable {
    private static final Logger LOG = Logger.getLogger(SearchService.class.getName());
    private static final long CLOSE_GRACE_SECONDS = 60;

    /** Everything needed to serve one version of the index. */
    record Engine(IndexReader reader, Searcher searcher, Suggester suggester, Signature signature) {
    }

    /** Identifies a version of the file without reading it. */
    record Signature(long lastModified, long size, Object fileKey) {
        static Signature of(Path path) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return new Signature(attributes.lastModifiedTime().toMillis(), attributes.size(), attributes.fileKey());
        }
    }

    private final Path path;
    private final long cacheBytes;
    private final AtomicReference<Engine> current = new AtomicReference<>();
    private final ScheduledExecutorService scheduler;
    private volatile Signature lastFailure;

    SearchService(Path path, long cacheBytes, int reloadSeconds) throws IOException {
        this.path = path;
        this.cacheBytes = cacheBytes;
        current.set(load());
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "index-reloader");
            thread.setDaemon(true);
            return thread;
        });
        if (reloadSeconds > 0) {
            scheduler.scheduleWithFixedDelay(this::reloadIfChanged, reloadSeconds, reloadSeconds, TimeUnit.SECONDS);
        }
    }

    Engine engine() {
        return current.get();
    }

    /** Reloads if the file changed. Returns true if a new index was swapped in. */
    synchronized boolean reloadIfChanged() {
        try {
            Signature signature = Signature.of(path);
            if (signature.equals(current.get().signature()) || signature.equals(lastFailure)) {
                return false;
            }
            Engine fresh = load();
            Engine old = current.getAndSet(fresh);
            lastFailure = null;
            LOG.info(() -> "Reloaded index: " + fresh.reader().documentCount() + " documents");
            scheduler.schedule(() -> closeQuietly(old), CLOSE_GRACE_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (IOException | RuntimeException e) {
            try {
                lastFailure = Signature.of(path);
            } catch (IOException ignored) {
                lastFailure = null;
            }
            LOG.log(Level.WARNING, "Keeping the current index; cannot load " + path + ": " + e.getMessage());
            return false;
        }
    }

    private Engine load() throws IOException {
        Signature before = Signature.of(path);
        IndexReader reader = IndexReader.open(path, cacheBytes);
        try {
            return new Engine(reader, new Searcher(reader), new Suggester(reader), Objects.requireNonNull(before));
        } catch (RuntimeException e) {
            reader.close();
            throw e;
        }
    }

    private static void closeQuietly(Engine engine) {
        try {
            engine.reader().close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "error closing old index", e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        closeQuietly(current.get());
    }
}
