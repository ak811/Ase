package io.github.ak811.ase.indexer;

import io.github.ak811.ase.core.index.IndexStats;
import io.github.ak811.ase.core.index.IndexWriter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Command-line entry point: corpus files in, index file out. */
public final class IndexerCli {
    static final int EXIT_OK = 0;
    static final int EXIT_FAILURE = 1;
    static final int EXIT_USAGE = 2;

    private static final int PROGRESS_EVERY = 10_000;

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
        Stats stats = new Stats();
        CorpusLoader loader = new CorpusLoader(new HtmlTextExtractor(), new WebIrXmlReader(), new JsonLinesReader());

        try (IndexWriter writer = new IndexWriter(options.output, options.writerConfig())) {
            DocumentSink sink = (url, title, body) -> {
                if (title.isEmpty() && body.isEmpty()) {
                    stats.emptyDocuments++;
                    return;
                }
                writer.addDocument(url, title, truncate(body, options.maxBodyChars));
                if (writer.documentCount() % PROGRESS_EVERY == 0) {
                    out.printf(Locale.ROOT, "  … %,d documents%n", writer.documentCount());
                }
            };
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
            if (writer.documentCount() == 0) {
                err.println("error: no documents with text were found in the input");
                return EXIT_FAILURE;
            }

            IndexStats index = writer.commit();
            double seconds = (System.nanoTime() - startNanos) / 1e9;
            out.printf(Locale.ROOT, "Indexed %,d documents (%,d distinct terms, %,d words for spelling) from %,d files in %.1f s%n",
                    index.documents(), index.terms(), index.lexiconWords(), stats.files, seconds);
            if (stats.emptyDocuments > 0) {
                out.printf(Locale.ROOT, "Skipped %,d documents without any text%n", stats.emptyDocuments);
            }
            if (stats.failedFiles > 0) {
                out.printf(Locale.ROOT, "Skipped %,d unreadable files%n", stats.failedFiles);
            }
            out.printf(Locale.ROOT, "Wrote %s (%,.1f KiB)%n", options.output, index.bytes() / 1024.0);
            return EXIT_OK;
        } catch (IOException e) {
            err.println("error: cannot write " + options.output + ": " + e.getMessage());
            return EXIT_FAILURE;
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

    private static final class Stats {
        int files;
        int failedFiles;
        int emptyDocuments;
    }
}
