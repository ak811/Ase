# UI smoke tests

Drives the real `index.html` and `app.js` in [jsdom](https://github.com/jsdom/jsdom) against a running
server: deep links, spelling notice, "search instead", back/forward, pagination, hostile data
(HTML in titles, `javascript:` URLs), the document viewer, autocomplete with the keyboard, and
the `/` shortcut.

```bash
# from the repository root
./gradlew :server:installDist :indexer:installDist
indexer/build/install/ase-indexer/bin/ase-indexer \
    -i sample-corpus -i ui-tests/fixtures/ui-extra.jsonl -o build/ui.idx
server/build/install/ase-server/bin/ase-server --index build/ui.idx --rate-limit 0 &

cd ui-tests && npm ci && npm test        # BASE_URL defaults to http://127.0.0.1:8080
```
