# Ase

An offline, on-device search engine for Persian text. A command-line indexer turns a
corpus (web pages, HTML files, or plain text) into a compact index file; the Android
app loads it and answers queries locally, with ranking, spelling correction, and
highlighted snippets. No network access or permissions are required.

> Example: `کتاپخانه` (misspelled, پ instead of ب) → *Showing results for* `کتابخانه`;
> `كتابخانه ملي` typed on an Arabic keyboard matches `کتابخانه ملی` directly.

## Why Persian needs special handling

- **One letter, several code points.** Text from Arabic keyboards and older software uses
  `ي`/`ك` where Persian uses `ی`/`ک`. They look the same, but a naive index treats them as
  different words.
- **Zero-width non-joiner (ZWNJ).** `می‌روم` and `میروم` are the same word; writers are
  inconsistent about the invisible U+200C.
- **Optional diacritics, tatweel (`ـ`), presentation-form glyphs, and Persian digits** (`۱۴۰۲`).
- **Typing errors follow the script.** Common typos swap letters that differ by a dot
  (`ب/پ`, `ک/گ`, `ج/چ`) or that sound identical (`س/ص/ث`, `ز/ذ/ض/ظ`, `ت/ط`, `ح/ه`).

Both documents and queries go through the same normalizer, so all of these match.

## Features

- TF-IDF ranking with title boosting and cosine length normalization
- AND semantics, falling back to partial matches (ranked by matched-term count) when no
  document contains every word
- Spelling correction that only suggests indexed words: confusable-letter substitution, then
  character-bigram candidates filtered by a weighted edit distance
- Query-focused snippets with highlighted matches; documents are never modified
- Versioned, validated, gzip-compressed binary index (≈3.5 KiB for the 10-document sample)
- Indexes WebIR XML dumps, `.html`/`.htm` files (via jsoup, with charset detection), and `.txt` files
- Android app: background loading and search, import your own index through the system
  file picker, Material 3 with dark mode and dynamic colour, RTL layout, English and Persian UI

## How it works

**Tokenization.** A token is a run of Arabic-script letters (ZWNJ allowed inside the word) or
a run of other letters/digits. Normalization unifies `ي ى → ی`, `ك → ک`, `ة ۀ → ه`,
`أ إ ٱ → ا`, `ؤ → و`, removes diacritics, tatweel, ZWNJ and bidi marks, folds presentation
forms (NFKC), converts Persian/Arabic digits to ASCII, and lower-cases Latin letters. Tokens
keep their offsets in the original text so matches can be highlighted exactly.

**Weighting** (computed once, at index time):

```
tf(t,d)  = count in body + titleBoost × count in title        (titleBoost defaults to 5)
idf(t)   = log10(1 + N / df(t))
w(t,d)   = (1 + log10 tf(t,d)) × idf(t),  then divided by ‖w(·,d)‖₂
score(d) = Σ w(t,d) over the query terms that d contains
```

**Retrieval.** Scores are accumulated term-at-a-time over the posting lists, and a bounded heap
selects the top results, so cost is proportional to the postings touched, not the corpus size.

**Spelling correction.** Only for query terms not in the index:
1. Replace one letter with a commonly confused one; if any variant is indexed, take the
   variant with the highest document frequency.
2. Otherwise collect indexed words sharing boundary-padded character bigrams
   (`^کت`, `کتا`, …), keep those with Jaccard similarity ≥ 0.3 and a length difference ≤ 2,
   and compute an optimal-string-alignment distance in which confusable substitutions cost 0.5.
   Accept distance ≤ 1 for words of up to 4 letters, ≤ 2 otherwise. Rank by distance, then
   document frequency.

The app shows *Showing results for X*; tapping it searches the original spelling instead.

## Project structure

```
search-core/   Pure Java library (Java 8 API, no Android dependencies)
  text/          PersianNormalizer, Tokenizer, Token
  index/         IndexBuilder, SearchIndex, PostingList, Document, IndexCodec
  spell/         SpellCorrector, EditDistance, ConfusableLetters
  search/        Searcher, SnippetGenerator, SearchOptions, SearchResult, SearchHit, Snippet
indexer/       Command-line tool that builds index files (uses jsoup for HTML)
app/           Android client (minSdk 24)
sample-corpus/ Ten short documents used to build the bundled demo index
```

The core library is usable on its own:

```java
IndexBuilder builder = new IndexBuilder();
builder.addDocument("https://example.com/tehran", "تهران", "تهران پایتخت ایران است.");
Searcher searcher = new Searcher(builder.build());
SearchResult result = searcher.search("تهرن");   // corrected to "تهران"
```

## Building an index

```bash
./gradlew :indexer:run --args="--input sample-corpus --output app/src/main/assets/index.bin"
```

Relative paths are resolved from the repository root. To get a standalone tool, run
`./gradlew :indexer:installDist` and use `indexer/build/install/ase-indexer/bin/ase-indexer`.

| Option | Meaning |
| --- | --- |
| `-i, --input <path>` | File or directory (recursive); repeatable |
| `-o, --output <file>` | Index file to write; replaced atomically |
| `--title-boost <n>` | Weight of a title occurrence relative to a body occurrence (default 5) |
| `--max-body-chars <n>` | Truncate stored bodies (limits index size); 0 = unlimited |
| `--skip-bad-files` | Warn and continue instead of stopping on an unreadable file |

Input formats:

- **`.xml`**, WebIR format: `<DOC><URL>…</URL><HTML>…</HTML></DOC>` records (HTML as CDATA or
  escaped text). Parsed as a stream with DTDs and external entities disabled.
- **`.html` / `.htm`**: one document per file; title from `<title>`; scripts and styles are dropped.
- **`.txt`**: one document per file, UTF-8; the first non-blank line is the title.

Local files are identified by their path relative to the input directory. In the app, results
with an `http(s)` URL open in the browser; other results show their text in a dialog.

## Running the app

Open the project in Android Studio and run the `app` configuration, or:

```bash
./gradlew :app:installDebug
```

The APK bundles `app/src/main/assets/index.bin` if present. Users can also load any index built
by the indexer from the overflow menu (**Import index…**); it is validated before it replaces
the current one.

## Index file format

Gzip-compressed stream: magic `ASEI`, format version, documents (URL, title, body as
length-prefixed UTF-8), then terms in sorted order, each with its document frequency,
delta-encoded varint document ids, and float32 weights. Reading validates the magic number,
version, id ordering and bounds, and trailing data. Bump `IndexCodec.FORMAT_VERSION` whenever
the layout or the normalization rules change, since existing indexes then need rebuilding.

## Testing

```bash
./gradlew :search-core:test :indexer:test
```

The tests cover normalization and tokenization, the index codec (round trip, determinism, and
corrupt input), ranking, the AND→partial fallback, spelling correction, snippets, and the
indexer end to end. CI (`.github/workflows/ci.yml`) runs them, rebuilds the sample index,
and assembles the debug APK.

## Limitations

- The whole index, including document bodies, is held in memory. This suits corpora up to
  tens of thousands of pages on a phone; use `--max-body-chars` to trim large corpora.
- Words split by a real space instead of a ZWNJ (`می خواهم` vs `می‌خواهم`) are two tokens, so
  they don't match the joined form.
- There is no stemming or stop-word removal, and no phrase or proximity search.

## License

MIT; see [license](license).
