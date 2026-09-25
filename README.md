# Ase
## Multilingual BM25 search engine in Java: SPIMI, positional phrase queries, Porter stemming, Jaccard spell correction

A self-hosted search engine with a web page and JSON API. Index your documents, then search them in
English, Persian (فارسی), Arabic, French, German, Spanish, Russian, Chinese, Japanese and most other
languages, with ranking, phrase search, spelling correction and autocomplete.

- **Multilingual analysis.** Words are split and normalized per writing system. The effects:
  - `CAFÉS` finds *café*.
  - `ك`/`ي` find `ک`/`ی`.
  - `می خواهم` finds `می‌خواهم`.
  - `ёлка` finds *елка*.
  - `東京大学` works without spaces.
- **Good ranking.** BM25 with a title boost, plus a boost when query words appear close together.
- **Query syntax.** `"exact phrases"`, `-exclusions` and `prefix*` wildcards.
- **Forgiving search.** Spelling correction ("Showing results for…"), stemming (`running` → `run`,
  `کتاب‌ها` → `کتاب`), and split compounds (`data base` → `database`, `کتاب خانه` → `کتابخانه`).
- **Scales past memory.** The index lives on disk. Indexing and serving both use bounded memory; see
  [Performance](#performance).
- **Operations.** It is production-minded:
  - One self-contained binary index file, replaced atomically.
  - Hot reload without downtime.
  - Checksums on the index structure.
  - Rate limiting and a strict Content-Security-Policy.
  - gzip/ETag, graceful shutdown and a Docker image.
- **Small footprint.** The engine and server use only the JDK; the indexer adds [jsoup](https://jsoup.org/)
  for HTML. The page is plain HTML/CSS/JS with no build step and no third-party requests.

## Quick start

### With Docker

```bash
docker compose up --build
# open http://localhost:8080/
```

The image contains a small multilingual [sample corpus](sample-corpus) so there is something to search
right away. [Your own documents](#index-your-own-documents) replace it.

### From source

Requires JDK 17 or newer.

```bash
./gradlew :server:run
# builds build/sample.idx from sample-corpus/ and serves it on http://127.0.0.1:8080/
```

## Index your own documents

```bash
./gradlew installDist
indexer/build/install/ase-indexer/bin/ase-indexer --input path/to/docs --output data/index.idx
server/build/install/ase-server/bin/ase-server --index data/index.idx
```

Each `--input` can be a file or a directory (searched recursively). Supported formats:

| Extension           | Documents                                                                       |
|---------------------|---------------------------------------------------------------------------------|
| `.jsonl`, `.ndjson` | One JSON object per line: `url` (or `id`), `title`, and `body`, `text` or `content` |
| `.html`, `.htm`     | One per file. The title comes from `<title>`; scripts and styles are ignored    |
| `.txt`, `.md`       | One per file. The first non-blank line is the title (a leading `#` is removed)  |
| `.xml`              | WebIR records: `<DOC><URL>…</URL><HTML>…</HTML></DOC>`                           |

JSON Lines is the easiest way to import from a database or crawler:

```json
{"url": "https://example.com/a", "title": "Tehran", "body": "Tehran is the capital of Iran…"}
```

Indexer options:

| Option                 | Default | Meaning                                                       |
|------------------------|---------|---------------------------------------------------------------|
| `--title-boost <n>`    | 3       | Weight of a word in the title relative to one in the body     |
| `--max-body-chars <n>` | 0       | Truncate long bodies (0 = keep everything)                    |
| `--no-stemming`        | off     | Index exact word forms only                                   |
| `--memory-mb <n>`      | 256     | Postings buffered in memory before spilling to temporary files |
| `--skip-bad-files`     | off     | Warn about unreadable files instead of stopping               |

Documents with an `http(s)` URL link to that page. Anything else (local files, ids) opens in a
built-in reader.

**Updating the index.** Run the indexer again with the same `--output` path. The new file is written
next to the old one and moved into place atomically; a running server picks it up within
`--reload-seconds` without dropping requests. If the new file is broken, the server keeps serving the
previous index and logs a warning.

## Search syntax

| Query              | Finds                                                                         |
|--------------------|-------------------------------------------------------------------------------|
| `search engines`   | Pages with both words (any form: *searching*, *engine*); best matches first   |
| `"inverted index"` | The exact phrase: those words, adjacent, in that order                        |
| `coffee -paris`    | Pages about coffee that do not mention Paris (`-"a phrase"` works too)         |
| `comput*`          | Any word starting with *comput*                                               |
| `the who`          | Queries made only of common words still work                                 |

If no page contains every word, pages containing some of them are shown, ranked by how many they
contain, with a note. Misspelled words that do not occur in the corpus are corrected
(`serch engnes` → `search engines`), with a link to search for the original instead.

## HTTP API

All endpoints are `GET` and return JSON.

| Endpoint                                   | Returns                                       |
|--------------------------------------------|-----------------------------------------------|
| `/api/search?q=…&page=1&size=10&exact=0`   | Ranked hits with highlight ranges             |
| `/api/suggest?q=…`                         | Up to 8 completions of the last word          |
| `/api/documents/{id}`                      | The stored document (body capped at 500k chars) |
| `/api/stats`                               | Document count, index size, build time        |
| `/healthz`                                 | `{"status":"ok"}` for load balancers           |

`size` is 1–50. `exact=1` disables spelling correction. Only the first 10,000 hits can be paged
through.

```json
{
  "query": "serch engnes",
  "correctedQuery": "search engines",
  "matchMode": "all",
  "total": 3,
  "page": 1,
  "size": 10,
  "tookMs": 0.84,
  "hits": [
    {
      "id": 3,
      "url": "en/search-engines.md",
      "title": {"text": "How search engines work", "highlights": [[4, 10], [11, 18]]},
      "snippet": {"text": "A search engine answers a query…", "highlights": [[2, 8], [9, 15]]},
      "score": 7.942
    }
  ]
}
```

Highlight ranges are `[start, end)` offsets in UTF-16 code units, which is how JavaScript indexes
strings. Errors look like `{"error": {"code": "bad_request", "message": "…"}}` with status 400, 404,
405, 429 (with `Retry-After`) or 500.

## Server configuration

Every option can be given as a flag or an environment variable.

| Flag                   | Environment               | Default     | Meaning                                              |
|------------------------|---------------------------|-------------|------------------------------------------------------|
| `--index`              | `ASE_INDEX`               | —           | Index file to serve (required)                       |
| `--host`               | `ASE_HOST`                | `127.0.0.1` | Interface to bind (`0.0.0.0` in Docker)              |
| `--port`               | `ASE_PORT` / `PORT`       | `8080`      | Port                                                 |
| `--threads`            | `ASE_THREADS`             | 2 × CPUs, min 4 | Request threads                                   |
| `--site-name`          | `ASE_SITE_NAME`           | `Ase`       | Name shown on the page                               |
| `--rate-limit`         | `ASE_RATE_LIMIT`          | `120`       | API requests per minute per client (0 = off)         |
| `--trust-proxy`        | `ASE_TRUST_PROXY`         | off         | Take the client address from `X-Forwarded-For`       |
| `--reload-seconds`     | `ASE_RELOAD_SECONDS`      | `10`        | How often to check the index file (0 = never)        |
| `--cache-mb`           | `ASE_CACHE_MB`            | `64`        | Cache for frequently used posting lists              |
|                        | `ASE_LOG_LEVEL`           | `INFO`      | `FINE` also logs every API request (path, status, time) |
|                        | `JAVA_OPTS`               |             | JVM options, e.g. `-Xmx512m`                         |

## How it works

### Text analysis

The same pipeline processes documents and queries, and its settings are stored in the index, so the
two always match.

1. **Segmentation.**
   - Text is split into words, each word being a run of letters from one writing system.
   - Numbers are separate words unless glued to Latin, Cyrillic or Greek letters (`mp3`, `covid19`).
   - Scripts written without spaces (Chinese, Japanese, Thai, Lao, Khmer, Burmese) are indexed as
     overlapping two-character pairs, so `東京大学` becomes `東京 · 京大 · 大学`.
2. **Normalization.**
   - All scripts: Unicode NFKC and case folding, and every kind of digit becomes `0–9`.
   - Latin and Greek lose accents (`café` → `cafe`, `ß` → `ss`).
   - Cyrillic folds `ё` → `е` and stress marks.
   - Hebrew loses vowel points.
   - Arabic-script letters are unified (`ي ى` → `ی`, `ك` → `ک`, `ة ۀ` → `ه`, `أ إ` → `ا`), and
     diacritics, tatweel and bidi marks are removed.
3. **Persian affixes.**
   - Prefixes (`می`, `نمی`) and suffixes (`ها`, `های`, `ترین`) written with a space are joined to their
     word.
   - As a result, `می خواهم`, `می‌خواهم` and `میخواهم` are the same term.
4. **Stemming** (optional).
   - English uses the [Porter stemmer](https://tartarus.org/martin/PorterStemmer/).
   - Persian uses a conservative light stemmer that removes only unambiguous suffixes. It leaves
     words like `تنها` or `دختر` alone.
5. **Stop words** (English, common European function words, Persian, Arabic, Russian) are marked,
   not removed. Queries don't require them, but phrase search can still match them.

### Index format

One file, written by `IndexWriter` and read by `IndexReader` (layout in `IndexFormat.java`):

```
header │ documents (deflate-compressed) │ postings │ dictionary │ lexicon │ document table │ footer
```

- **Postings** list, for each term, the documents containing it with title and body frequencies and
  word positions. They are encoded as delta varints.
- **Memory while indexing.** Postings are buffered up to `--memory-mb`, written to sorted temporary
  runs, and k-way merged at the end, so memory use does not grow with the corpus.
- **Memory while serving.** The server loads only the dictionary, the lexicon (the words used for
  spelling and autocomplete) and one offset and length per document. Postings and documents are read
  from disk on demand, with an LRU cache.
- **Integrity.** The dictionary, lexicon and document table are covered by a CRC-32 that is checked
  on open. A version number and an analyzer-rules version reject incompatible files instead of
  returning wrong results.

### Query evaluation and ranking

1. **Parsing.** A query becomes required clauses plus exclusions. A clause matches if any of its
   alternatives matches; for example, `data base` is one clause, "`database`, or both `data` and
   `base`".
2. **Matching.** Clauses are evaluated document-at-a-time over the posting lists of the query terms,
   so the cost depends on those lists, not on the size of the corpus. Phrases are verified with
   positions.
3. **Scoring.** Each document is scored with BM25 (k1 = 1.2, b = 0.75). Term frequency counts title
   occurrences `--title-boost` times, and document length is weighted the same way.
4. **Fallback.** If no document matches every clause, documents matching some clauses are ranked
   first by how many they match.
5. **Proximity.** The top 100 hits are re-ranked by a proximity boost of up to 1.5×, based on the
   smallest window containing all matched clauses.

### Spelling and autocomplete

- **Spelling.** A word that is missing from the index is corrected to a corpus word. For Persian,
  commonly confused letters are tried first (`کتاپ` → `کتاب`). Otherwise candidates come from a
  character-bigram index, filtered by edit distance and ranked by distance and popularity.
  Suggestions always come from the corpus, so a corrected query has results.
- **Autocomplete.** The last word of the query is completed with the most frequent corpus words that
  start with it.

## Performance

Measured on synthetic data: 200,000 documents of 120 words each from a 60,000-word vocabulary with a
Zipf distribution. The JVM heap was limited to 384 MB (`-Xmx384m`); the CPU was a shared cloud
container, so treat these numbers as orders of magnitude.

| Step                                   | Result                                         |
|----------------------------------------|------------------------------------------------|
| Indexing                               | 28 s (~7,000 docs/s), 3 spilled runs, 171 MB file |
| Heap used by the open index and searcher | 12.5 MB                                      |
| Typical two-word query                 | 1–12 ms                                        |
| Phrase or query of very common words (tens of thousands of hits) | 30–45 ms              |

The index file is larger than the raw text mostly because it stores the (compressed) documents
themselves plus word positions for phrase search.

## Deployment notes

- **TLS and proxying.** Put the server behind a reverse proxy (nginx, Caddy, a cloud load balancer)
  for TLS. Use `--trust-proxy` only there. It makes rate limiting use the last `X-Forwarded-For` hop,
  i.e. the address your proxy saw.
- **Response headers.** Every response carries `Content-Security-Policy` (only same-origin scripts,
  styles and requests), `X-Content-Type-Options`, `X-Frame-Options` and `Referrer-Policy`.
- **Untrusted content.** The page never interprets document content as HTML, and links are only
  created for `http(s)` URLs.
- **Memory.** The server needs roughly the dictionary and lexicon size (see the numbers above) plus
  `--cache-mb`. The operating system's page cache does the rest, so leave some RAM free for it.
- **Timeouts.** Request and response timeouts default to 30 and 60 seconds (JDK HTTP server
  properties `sun.net.httpserver.maxReqTime` / `maxRspTime`).
- **Shutdown and health.** `SIGTERM` stops accepting connections, gives in-flight requests up to two
  seconds to finish, and exits. `/healthz` is suitable for liveness and readiness checks.

## Development

```
search-core/   analysis, index format, searcher, spelling, autocomplete   (no dependencies)
indexer/       ase-indexer command-line tool                              (jsoup)
server/        ase-server: HTTP API + static page in src/main/resources/public/
ui-tests/      jsdom smoke tests for the page
sample-corpus/ small documents in 10 languages (Persian in fa/, English in en/, others as JSON Lines)
```

```bash
./gradlew build            # compile and run all JUnit tests
./gradlew :server:run      # serve the sample corpus
./gradlew sampleIndex      # only build build/sample.idx
```

The page is plain files in `server/src/main/resources/public/`; restart the server to see edits.
The UI tests are described in [ui-tests/README.md](ui-tests/README.md). CI
(`.github/workflows/ci.yml`) runs the unit tests, starts the packaged server, runs API and UI smoke
tests, and builds and runs the Docker image.

## Limitations and trade-offs

- **Chinese, Japanese and Thai** use character pairs instead of dictionary-based word segmentation.
  Recall is good and nothing extra needs to be installed. Precision is lower than with a proper
  segmenter, and single-character queries only match single-character words.
- **Stemming** covers English and Persian only. Other languages are matched by normalized word form:
  accents and case do not matter, but Russian `книги` does not find `книга`. The Persian stemmer is
  deliberately light; it doesn't handle verb conjugation or Arabic broken plurals.
- **Spelling correction** considers the 300,000 most frequent corpus words and corrects words that are
  not in the corpus. A correctly spelled but unintended word is not changed.
- **Updates rebuild the whole index.** That is simple and robust up to millions of documents. There is
  no incremental add/delete.
- **One server process.** For more traffic, run several instances behind a load balancer, each
  reading its own copy of the index file.

## History

The project started in 2019 as an Android app that searched Persian text with TF-IDF and bigram spell
correction, holding the whole index in memory. Version 2 is a rewrite as a multilingual web search
engine: disk-based index, BM25, phrase and proximity search, stemming, and a web page and API in
place of the Android app.

## License

MIT — see [license](license).
