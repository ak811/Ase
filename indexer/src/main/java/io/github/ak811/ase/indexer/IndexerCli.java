package io.github.ak811.ase.indexer;

import io.github.ak811.ase.core.index.IndexBuilder;
import io.github.ak811.ase.core.index.IndexCodec;
import io.github.ak811.ase.core.index.SearchIndex;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/** Command-line entry point: corpus files in, binary index file out. */
public final class IndexerCli {
    static final int EXIT_OK = 0;
    static final int EXIT_FAILURE = 1;
    static final int EXIT_USAGE = 2;

    private IndexerCli() {
    }

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != EXIT_OK) {
            System.exit(code);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.println();
            err.println(CliOptions.usage());
            return EXIT_USAGE;
        }
        if (options.help) {
            out.println(CliOptions.usage());
            return EXIT_OK;
        }

        long startNanos = System.nanoTime();
        IndexBuilder builder = new IndexBuilder(options.titleBoost);
        Stats stats = new Stats();
        DocumentSink sink = (url, title, body) -> {
            if (title.isEmpty() && body.isEmpty()) {
                stats.emptyDocuments++;
                return;
            }
            builder.addDocument(url, title, truncate(body, options.maxBodyChars));
        };

        CorpusLoader loader = new CorpusLoader(new HtmlTextExtractor(), new WebIrXmlReader());
        for (Path input : options.inputs) {
            List<Path> files;
            try {
                files = loader.listFiles(input);
            } catch (IOException e) {
                err.println("error: " + e.getMessage());
                return EXIT_FAILURE;
            }
            for (Path file : files) {
                try {
                    loader.loadFile(input, file, sink);
                    stats.files++;
                } catch (IOException | RuntimeException e) {
                    if (!options.skipBadFiles) {
                        err.println("error: cannot read " + file + ": " + e.getMessage());
                        err.println("hint: pass --skip-bad-files to skip unreadable files");
                        return EXIT_FAILURE;
                    }
                    err.println("warning: skipping " + file + ": " + e.getMessage());
                    stats.failedFiles++;
                }
            }
        }

        if (builder.documentCount() == 0) {
            err.println("error: no documents with text were found in the input");
            return EXIT_FAILURE;
        }

        SearchIndex index = builder.build();
        try {
            writeAtomically(index, options.output);
        } catch (IOException e) {
            err.println("error: cannot write " + options.output + ": " + e.getMessage());
            return EXIT_FAILURE;
        }

        double seconds = (System.nanoTime() - startNanos) / 1e9;
        long bytes = sizeOf(options.output);
        out.printf(Locale.ROOT, "Indexed %,d documents (%,d distinct terms) from %,d files in %.1f s%n",
                index.documentCount(), index.termCount(), stats.files, seconds);
        if (stats.emptyDocuments > 0) {
            out.printf(Locale.ROOT, "Skipped %,d documents without any text%n", stats.emptyDocuments);
        }
        if (stats.failedFiles > 0) {
            out.printf(Locale.ROOT, "Skipped %,d unreadable files%n", stats.failedFiles);
        }
        out.printf(Locale.ROOT, "Wrote %s (%,.1f KiB)%n", options.output, bytes / 1024.0);
        return EXIT_OK;
    }

    private static void writeAtomically(SearchIndex index, Path output) throws IOException {
        Path target = output.toAbsolutePath();
        Path directory = target.getParent();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, target.getFileName().toString() + ".", ".tmp");
        try {
            try (OutputStream stream = Files.newOutputStream(temp)) {
                IndexCodec.write(index, stream);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static String truncate(String text, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int end = maxChars;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static final class Stats {
        int files;
        int failedFiles;
        int emptyDocuments;
    }
}
