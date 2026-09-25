package io.github.ak811.ase.indexer;

import io.github.ak811.ase.core.index.IndexBuilder;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parsed command-line options. */
final class CliOptions {
    final List<Path> inputs;
    final Path output;
    final float titleBoost;
    final int maxBodyChars;
    final boolean skipBadFiles;
    final boolean help;

    private CliOptions(List<Path> inputs, Path output, float titleBoost, int maxBodyChars,
                       boolean skipBadFiles, boolean help) {
        this.inputs = Collections.unmodifiableList(inputs);
        this.output = output;
        this.titleBoost = titleBoost;
        this.maxBodyChars = maxBodyChars;
        this.skipBadFiles = skipBadFiles;
        this.help = help;
    }

    static CliOptions parse(String[] args) {
        List<Path> inputs = new ArrayList<>();
        Path output = null;
        float titleBoost = IndexBuilder.DEFAULT_TITLE_BOOST;
        int maxBodyChars = 0;
        boolean skipBadFiles = false;
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String inlineValue = null;
            if (arg.startsWith("--") && arg.contains("=")) {
                inlineValue = arg.substring(arg.indexOf('=') + 1);
                arg = arg.substring(0, arg.indexOf('='));
            }
            switch (arg) {
                case "-i":
                case "--input":
                    inputs.add(Paths.get(value(args, inlineValue, i, arg)));
                    if (inlineValue == null) i++;
                    break;
                case "-o":
                case "--output":
                    output = Paths.get(value(args, inlineValue, i, arg));
                    if (inlineValue == null) i++;
                    break;
                case "--title-boost":
                    titleBoost = parseFloat(value(args, inlineValue, i, arg), arg);
                    if (inlineValue == null) i++;
                    if (!(titleBoost >= 1f) || Float.isInfinite(titleBoost)) {
                        throw new IllegalArgumentException("--title-boost must be a number >= 1");
                    }
                    break;
                case "--max-body-chars":
                    maxBodyChars = parseInt(value(args, inlineValue, i, arg), arg);
                    if (inlineValue == null) i++;
                    if (maxBodyChars < 0) {
                        throw new IllegalArgumentException("--max-body-chars must be >= 0");
                    }
                    break;
                case "--skip-bad-files":
                    skipBadFiles = true;
                    break;
                case "-h":
                case "--help":
                    help = true;
                    break;
                default:
                    throw new IllegalArgumentException("unknown option: " + arg);
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
        return new CliOptions(inputs, output, titleBoost, maxBodyChars, skipBadFiles, help);
    }

    static String usage() {
        return String.join(System.lineSeparator(),
                "Usage: ase-indexer --input <path> [--input <path> ...] --output <index.bin> [options]",
                "",
                "Builds a search index from a corpus. Each input may be a file or a directory",
                "(searched recursively). Supported files:",
                "  .xml         WebIR records: <DOC><URL>…</URL><HTML>…</HTML></DOC>",
                "  .html .htm   one document per file, title from <title>",
                "  .txt         one document per file, first non-blank line is the title (UTF-8)",
                "",
                "Options:",
                "  -i, --input <path>        corpus file or directory (repeatable)",
                "  -o, --output <file>       index file to write (replaced atomically)",
                "      --title-boost <n>     weight of a title occurrence vs. a body occurrence (default "
                        + IndexBuilder.DEFAULT_TITLE_BOOST + ")",
                "      --max-body-chars <n>  truncate stored bodies to n characters; 0 = unlimited (default 0)",
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
