package io.github.ak811.ase.indexer;

import io.github.ak811.ase.core.index.IndexWriterConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Parsed command-line options. */
final class CliOptions {
    static final int DEFAULT_MEMORY_MB = 256;

    final List<Path> inputs;
    final Path output;
    final float titleBoost;
    final int maxBodyChars;
    final boolean stemming;
    final int memoryMb;
    final boolean skipBadFiles;
    final boolean help;

    private CliOptions(List<Path> inputs, Path output, float titleBoost, int maxBodyChars, boolean stemming,
                       int memoryMb, boolean skipBadFiles, boolean help) {
        this.inputs = List.copyOf(inputs);
        this.output = output;
        this.titleBoost = titleBoost;
        this.maxBodyChars = maxBodyChars;
        this.stemming = stemming;
        this.memoryMb = memoryMb;
        this.skipBadFiles = skipBadFiles;
        this.help = help;
    }

    static CliOptions parse(String[] args) {
        List<Path> inputs = new ArrayList<>();
        Path output = null;
        float titleBoost = IndexWriterConfig.DEFAULT_TITLE_BOOST;
        int maxBodyChars = 0;
        boolean stemming = true;
        int memoryMb = DEFAULT_MEMORY_MB;
        boolean skipBadFiles = false;
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String inlineValue = null;
            if (arg.startsWith("--") && arg.contains("=")) {
                inlineValue = arg.substring(arg.indexOf('=') + 1);
                arg = arg.substring(0, arg.indexOf('='));
            }
            boolean consumesNext = inlineValue == null;
            switch (arg) {
                case "-i":
                case "--input":
                    inputs.add(Path.of(value(args, inlineValue, i, arg)));
                    break;
                case "-o":
                case "--output":
                    output = Path.of(value(args, inlineValue, i, arg));
                    break;
                case "--title-boost":
                    titleBoost = parseFloat(value(args, inlineValue, i, arg), arg);
                    if (!(titleBoost >= 1f) || Float.isInfinite(titleBoost)) {
                        throw new IllegalArgumentException("--title-boost must be a number >= 1");
                    }
                    break;
                case "--max-body-chars":
                    maxBodyChars = parseInt(value(args, inlineValue, i, arg), arg);
                    if (maxBodyChars < 0) {
                        throw new IllegalArgumentException("--max-body-chars must be >= 0");
                    }
                    break;
                case "--memory-mb":
                    memoryMb = parseInt(value(args, inlineValue, i, arg), arg);
                    if (memoryMb < 16) {
                        throw new IllegalArgumentException("--memory-mb must be at least 16");
                    }
                    break;
                case "--no-stemming":
                    stemming = false;
                    consumesNext = false;
                    break;
                case "--skip-bad-files":
                    skipBadFiles = true;
                    consumesNext = false;
                    break;
                case "-h":
                case "--help":
                    help = true;
                    consumesNext = false;
                    break;
                default:
                    throw new IllegalArgumentException("unknown option: " + arg);
            }
            if (consumesNext) {
                i++;
            }
        }
        if (!help) {
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("at least one --input is required");
            }
            if (output == null) {
                throw new IllegalArgumentException("--output is required");
            }
        }
        return new CliOptions(inputs, output, titleBoost, maxBodyChars, stemming, memoryMb, skipBadFiles, help);
    }

    IndexWriterConfig writerConfig() {
        IndexWriterConfig defaults = IndexWriterConfig.defaults();
        return defaults.withAnalyzer(new io.github.ak811.ase.core.analysis.AnalyzerConfig(stemming))
                .withTitleBoost(titleBoost)
                .withMemoryBudgetBytes(memoryMb * 1024L * 1024L);
    }

    static String usage() {
        return String.join(System.lineSeparator(),
                "Usage: ase-indexer --input <path> [--input <path> ...] --output <index.idx> [options]",
                "",
                "Builds a search index from a corpus in any language. Each input may be a file or a",
                "directory (searched recursively). Supported files:",
                "  .xml            WebIR records: <DOC><URL>…</URL><HTML>…</HTML></DOC>",
                "  .html .htm      one document per file, title from <title>",
                "  .txt .md        one document per file, first non-blank line is the title (UTF-8)",
                "  .jsonl .ndjson  one JSON object per line: {\"url\", \"title\", \"body\"|\"text\"|\"content\"}",
                "",
                "Options:",
                "  -i, --input <path>        corpus file or directory (repeatable)",
                "  -o, --output <file>       index file to write (replaced atomically)",
                "      --title-boost <n>     weight of a title occurrence vs. a body occurrence (default "
                        + IndexWriterConfig.DEFAULT_TITLE_BOOST + ")",
                "      --max-body-chars <n>  truncate bodies to n characters; 0 = unlimited (default 0)",
                "      --no-stemming         index exact word forms (no English/Persian stemming)",
                "      --memory-mb <n>       postings buffer before spilling to disk (default " + DEFAULT_MEMORY_MB + ")",
                "      --skip-bad-files      warn and continue when a file cannot be read",
                "  -h, --help                show this help");
    }

    private static String value(String[] args, String inlineValue, int index, String option) {
        if (inlineValue != null) {
            if (inlineValue.isEmpty()) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return inlineValue;
        }
        if (index + 1 >= args.length || args[index + 1].startsWith("-")) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index + 1];
    }

    private static float parseFloat(String value, String option) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " expects a number, got '" + value + "'");
        }
    }

    private static int parseInt(String value, String option) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " expects an integer, got '" + value + "'");
        }
    }
}
