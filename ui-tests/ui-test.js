// Browser-level smoke test of the search page, using jsdom against a running server.
// Needs an index built from sample-corpus/ plus fixtures/ui-extra.jsonl; see ui-tests/README.md.
const { JSDOM, VirtualConsole } = require('jsdom');
const BASE = process.env.BASE_URL || 'http://127.0.0.1:8080';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let failures = 0;
function check(name, cond, detail) {
  console.log((cond ? 'PASS ' : 'FAIL ') + name + (cond || detail === undefined ? '' : '  -> ' + detail));
  if (!cond) failures++;
}
async function open(path) {
  const errors = [];
  const vc = new VirtualConsole();
  vc.on('jsdomError', e => errors.push(e.message));
  vc.on('error', e => errors.push(String(e)));
  const dom = await JSDOM.fromURL(BASE + path, {
    runScripts: 'dangerously', resources: 'usable', pretendToBeVisual: true, virtualConsole: vc,
    beforeParse(window) {
      // jsdom has no fetch; delegate to Node's, resolving relative URLs against the page.
      window.fetch = (url, opts = {}) => fetch(new URL(url, window.location.href), { headers: opts.headers });
    }
  });
  await new Promise(r => dom.window.addEventListener('load', r));
  await sleep(400);
  return { dom, w: dom.window, d: dom.window.document, errors };
}

(async () => {
  // 1. Home page
  let { w, d, errors } = await open('/');
  check('home: body.is-home', d.body.classList.contains('is-home'));
  check('home: title is the site name', d.title === 'Ase', d.title);
  check('home: footer stats loaded', /\d+ documents indexed/.test(d.getElementById('footer-stats').textContent), d.getElementById('footer-stats').textContent);
  check('home: no script errors', errors.length === 0, errors.join('; '));

  // 2. Deep link with a misspelled query
  ({ w, d, errors } = await open('/?q=serch%20engnes'));
  const hits = d.querySelectorAll('#results .result');
  check('results rendered', hits.length > 0, hits.length);
  check('not home', !d.body.classList.contains('is-home'));
  check('correction shown', /Showing results for search engines/.test(d.getElementById('correction').textContent), d.getElementById('correction').textContent);
  const instead = [...d.querySelectorAll('#correction a')].find(a => a.textContent === 'serch engnes');
  check('"search instead" link uses exact=1', instead && instead.getAttribute('href').includes('exact=1'), instead && instead.getAttribute('href'));
  check('status shows count and time', /result.*ms\)/.test(d.getElementById('status').textContent), d.getElementById('status').textContent);
  check('matches are wrapped in <mark>', d.querySelectorAll('#results mark').length > 0);
  check('document title includes the query', d.title.startsWith('serch engnes'), d.title);
  check('input reflects the query', d.getElementById('q').value === 'serch engnes');

  // 3. Click "search instead" (client-side navigation, exact mode)
  instead.dispatchEvent(new w.MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
  await sleep(400);
  check('exact search updates the URL', w.location.search.includes('exact=1'), w.location.search);
  check('exact search shows the empty state', /No results for/.test(d.getElementById('results').textContent), d.getElementById('results').textContent.slice(0, 80));
  check('exact search has no correction', d.getElementById('correction').hidden);

  // 4. Back button restores the previous search
  w.history.back();
  await sleep(500);
  check('back restores the corrected search', d.querySelectorAll('#results .result').length > 0 && !w.location.search.includes('exact'), w.location.search);

  // 5. Pagination
  ({ w, d, errors } = await open('/?q=pagination'));
  check('page 1 has 10 results', d.querySelectorAll('#results .result').length === 10);
  const pager = d.getElementById('pager');
  check('pager visible', !pager.hidden);
  check('current page marked', pager.querySelector('[aria-current="page"]').textContent === '1');
  const next = [...pager.querySelectorAll('a')].find(a => a.textContent === 'Next');
  next.dispatchEvent(new w.MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
  await sleep(400);
  check('next page navigates', w.location.search.includes('page=2') && pager.querySelector('[aria-current="page"]').textContent === '2', w.location.search);
  check('markup in documents is shown as text', d.getElementById('results').innerHTML.includes('&lt;b&gt;markup&lt;/b&gt;'));

  // 6. Hostile data: javascript: URL and HTML in titles
  ({ w, d, errors } = await open('/?q=hostile'));
  const hostile = d.querySelector('#results .result');
  check('hostile title rendered as text', hostile.querySelector('.result-title').textContent.includes('<img src=x onerror=alert(1)>'));
  check('no <img> injected', d.querySelectorAll('#results img').length === 0);
  check('javascript: URL is not a link', !hostile.querySelector('a[href^="javascript"]') && hostile.querySelector('.result-title button') !== null);

  // 7. Local document viewer
  ({ w, d, errors } = await open('/?q=' + encodeURIComponent('کتابخانه')));
  const fa = d.querySelector('#results .result');
  check('Persian result uses dir=auto', fa.querySelector('.result-title').getAttribute('dir') === 'auto');
  fa.querySelector('.result-title button').click();
  await sleep(400);
  check('viewer opens with the document', d.getElementById('viewer').hasAttribute('open') && d.getElementById('viewer-body').textContent.length > 20, d.getElementById('viewer-title').textContent);
  d.getElementById('viewer-close').click();
  check('viewer closes', !d.getElementById('viewer').hasAttribute('open'));

  // 8. Autocomplete with keyboard selection
  ({ w, d, errors } = await open('/'));
  const input = d.getElementById('q');
  input.focus();
  input.value = 'sea';
  input.dispatchEvent(new w.Event('input', { bubbles: true }));
  await sleep(500);
  const options = d.querySelectorAll('#suggestions [role="option"]');
  check('suggestions shown', options.length > 0 && !d.getElementById('suggestions').hidden, options.length);
  check('combobox expanded', input.getAttribute('aria-expanded') === 'true');
  input.dispatchEvent(new w.KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
  check('arrow key selects first option', options[0].getAttribute('aria-selected') === 'true' && input.getAttribute('aria-activedescendant') === options[0].id);
  for (let i = 0; i < options.length; i++) input.dispatchEvent(new w.KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
  check('arrow keys wrap to "nothing selected"', [...options].every(o => o.getAttribute('aria-selected') === 'false') && !input.hasAttribute('aria-activedescendant'));
  input.dispatchEvent(new w.KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }));
  check('ArrowUp from nothing selects the last option', options[options.length - 1].getAttribute('aria-selected') === 'true');
  input.dispatchEvent(new w.KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
  input.dispatchEvent(new w.KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
  const chosen = options[0].textContent;
  d.getElementById('search-form').dispatchEvent(new w.Event('submit', { bubbles: true, cancelable: true }));
  await sleep(500);
  check('Enter searches the chosen suggestion', new w.URLSearchParams(w.location.search).get('q') === chosen, w.location.search);
  check('suggestions close after choosing', d.getElementById('suggestions').hidden);

  // 9. Submitting an empty query returns home
  input.value = '   ';
  d.getElementById('search-form').dispatchEvent(new w.Event('submit', { bubbles: true, cancelable: true }));
  await sleep(200);
  check('empty query shows home', d.body.classList.contains('is-home') && w.location.search === '');

  // 10. "/" shortcut
  input.blur();
  d.body.dispatchEvent(new w.KeyboardEvent('keydown', { key: '/', bubbles: true }));
  check('"/" focuses the search box', d.activeElement === input);

  check('no script errors anywhere', errors.length === 0, errors.join('; '));
  console.log(failures === 0 ? '\nALL UI CHECKS PASSED' : '\n' + failures + ' UI CHECK(S) FAILED');
  process.exit(failures ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
