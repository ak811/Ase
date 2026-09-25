/* Search page. No dependencies; every piece of result text is inserted with textContent. */
(function () {
  'use strict';

  var PAGE_SIZE = 10;
  var MAX_RESULTS = 10000;
  var SUGGEST_DELAY_MS = 120;

  var el = {
    body: document.body,
    form: document.getElementById('search-form'),
    input: document.getElementById('q'),
    suggestions: document.getElementById('suggestions'),
    status: document.getElementById('status'),
    correction: document.getElementById('correction'),
    results: document.getElementById('results'),
    pager: document.getElementById('pager'),
    tagline: document.getElementById('tagline'),
    footer: document.getElementById('footer-stats'),
    siteName: document.getElementById('site-name'),
    viewer: document.getElementById('viewer'),
    viewerTitle: document.getElementById('viewer-title'),
    viewerMeta: document.getElementById('viewer-meta'),
    viewerBody: document.getElementById('viewer-body'),
    viewerClose: document.getElementById('viewer-close')
  };

  var siteName = el.siteName.textContent.trim() || 'Search';
  var searchController = null;
  var suggestController = null;
  var suggestTimer = 0;
  var activeSuggestion = -1;
  var numberFormat = new Intl.NumberFormat();

  // ------------------------------------------------------------------ state

  function readState() {
    var params = new URLSearchParams(window.location.search);
    var page = parseInt(params.get('page') || '1', 10);
    return {
      q: (params.get('q') || '').trim(),
      page: isFinite(page) && page > 0 ? page : 1,
      exact: params.get('exact') === '1'
    };
  }

  function urlFor(state) {
    var params = new URLSearchParams();
    if (state.q) params.set('q', state.q);
    if (state.page > 1) params.set('page', String(state.page));
    if (state.exact) params.set('exact', '1');
    var query = params.toString();
    return '/' + (query ? '?' + query : '');
  }

  function navigate(state) {
    var url = urlFor(state);
    if (url !== window.location.pathname + window.location.search) {
      window.history.pushState(null, '', url);
    }
    render(state);
  }

  function render(state) {
    el.input.value = state.q;
    closeSuggestions();
    if (!state.q) {
      showHome();
      return;
    }
    el.body.classList.remove('is-home');
    document.title = state.q + ' – ' + siteName;
    search(state);
  }

  function showHome() {
    if (searchController) searchController.abort();
    el.body.classList.add('is-home');
    document.title = siteName;
    el.results.textContent = '';
    el.status.textContent = '';
    el.correction.hidden = true;
    el.pager.hidden = true;
  }

  // ------------------------------------------------------------------ search

  function search(state) {
    if (searchController) searchController.abort();
    searchController = new AbortController();
    var params = new URLSearchParams({ q: state.q, page: String(state.page), size: String(PAGE_SIZE) });
    if (state.exact) params.set('exact', '1');

    el.body.classList.add('is-loading');
    el.results.setAttribute('aria-busy', 'true');
    fetch('/api/search?' + params.toString(), { signal: searchController.signal, headers: { Accept: 'application/json' } })
      .then(function (response) {
        return response.json().catch(function () { return {}; }).then(function (data) {
          if (!response.ok) {
            var error = new Error((data.error && data.error.message) || 'The search failed (HTTP ' + response.status + ').');
            error.status = response.status;
            error.retryAfter = response.headers.get('Retry-After');
            throw error;
          }
          return data;
        });
      })
      .then(function (data) { renderResults(state, data); })
      .catch(function (error) {
        if (error.name === 'AbortError') return;
        renderError(error);
      })
      .finally(function () {
        el.body.classList.remove('is-loading');
        el.results.removeAttribute('aria-busy');
      });
  }

  function renderResults(state, data) {
    el.results.textContent = '';
    el.status.classList.remove('is-error');

    renderCorrection(state, data);

    if (!data.hits || data.hits.length === 0) {
      el.status.textContent = '';
      el.pager.hidden = true;
      el.results.appendChild(emptyState(data.correctedQuery || state.q, data.total > 0));
      return;
    }

    var summary = (data.total === 1 ? '1 result' : 'About ' + numberFormat.format(data.total) + ' results')
      + ' (' + formatMillis(data.tookMs) + ')';
    if (data.matchMode === 'any') {
      summary += ' · No page contains every word, so these match some of them.';
    }
    el.status.textContent = summary;

    data.hits.forEach(function (hit) { el.results.appendChild(resultItem(hit)); });
    renderPager(state, data.total);
  }

  function renderCorrection(state, data) {
    el.correction.textContent = '';
    if (!data.correctedQuery) {
      el.correction.hidden = true;
      return;
    }
    el.correction.appendChild(document.createTextNode('Showing results for '));
    var corrected = link(urlFor({ q: data.correctedQuery, page: 1, exact: false }), data.correctedQuery);
    corrected.dir = 'auto';
    el.correction.appendChild(corrected);

    var instead = document.createElement('span');
    instead.className = 'instead';
    instead.appendChild(document.createTextNode('Search instead for '));
    var original = link(urlFor({ q: state.q, page: 1, exact: true }), state.q);
    original.dir = 'auto';
    instead.appendChild(original);
    el.correction.appendChild(instead);
    el.correction.hidden = false;
  }

  function emptyState(query, beyondLastPage) {
    var box = document.createElement('li');
    box.className = 'empty';
    var heading = document.createElement('h2');
    if (beyondLastPage) {
      heading.textContent = 'There are no more results.';
      box.appendChild(heading);
      return box;
    }
    heading.appendChild(document.createTextNode('No results for “'));
    var q = document.createElement('bdi');
    q.textContent = query;
    heading.appendChild(q);
    heading.appendChild(document.createTextNode('”'));
    box.appendChild(heading);
    var tips = document.createElement('ul');
    ['Check the spelling or try different words.', 'Use fewer or more general words.',
     'Use prefix* to match word endings, e.g. comput*.'].forEach(function (tip) {
      var item = document.createElement('li');
      item.textContent = tip;
      tips.appendChild(item);
    });
    box.appendChild(tips);
    return box;
  }

  function resultItem(hit) {
    var item = document.createElement('li');
    item.className = 'result';

    var source = document.createElement('p');
    source.className = 'result-source';
    source.dir = 'auto';
    source.textContent = displaySource(hit.url);
    item.appendChild(source);

    var title = document.createElement('h3');
    title.className = 'result-title';
    title.dir = 'auto';
    var titleSnippet = hit.title && hit.title.text ? hit.title : { text: hit.url || 'Untitled document', highlights: [] };
    var target;
    var safeUrl = httpUrl(hit.url);
    if (safeUrl) {
      target = document.createElement('a');
      target.href = safeUrl;
      target.rel = 'noopener noreferrer';
    } else {
      target = document.createElement('button');
      target.type = 'button';
      target.addEventListener('click', function () { openDocument(hit.id); });
    }
    target.appendChild(highlighted(titleSnippet));
    title.appendChild(target);
    item.appendChild(title);

    if (hit.snippet && hit.snippet.text) {
      var snippet = document.createElement('p');
      snippet.className = 'result-snippet';
      snippet.dir = 'auto';
      snippet.appendChild(highlighted(hit.snippet));
      item.appendChild(snippet);
    }
    return item;
  }

  /** Text with <mark> around the highlight ranges (UTF-16 offsets, as sent by the server). */
  function highlighted(snippet) {
    var fragment = document.createDocumentFragment();
    var text = snippet.text || '';
    var ranges = (snippet.highlights || []).slice().sort(function (a, b) { return a[0] - b[0]; });
    var cursor = 0;
    ranges.forEach(function (range) {
      var start = Math.max(cursor, Math.min(range[0], text.length));
      var end = Math.min(Math.max(range[1], start), text.length);
      if (end <= start) return;
      if (start > cursor) fragment.appendChild(document.createTextNode(text.slice(cursor, start)));
      var mark = document.createElement('mark');
      mark.textContent = text.slice(start, end);
      fragment.appendChild(mark);
      cursor = end;
    });
    if (cursor < text.length) fragment.appendChild(document.createTextNode(text.slice(cursor)));
    return fragment;
  }

  function renderPager(state, total) {
    el.pager.textContent = '';
    var pages = Math.ceil(Math.min(total, MAX_RESULTS) / PAGE_SIZE);
    if (pages <= 1) {
      el.pager.hidden = true;
      return;
    }
    var current = Math.min(state.page, pages);
    var first = Math.max(1, Math.min(current - 4, pages - 9));
    var last = Math.min(pages, first + 9);

    el.pager.appendChild(pageLink(state, current - 1, 'Previous', current <= 1));
    for (var page = first; page <= last; page++) {
      var node = pageLink(state, page, String(page), false);
      if (page === current) node.setAttribute('aria-current', 'page');
      el.pager.appendChild(node);
    }
    el.pager.appendChild(pageLink(state, current + 1, 'Next', current >= pages));
    el.pager.hidden = false;
  }

  function pageLink(state, page, label, disabled) {
    if (disabled) {
      var span = document.createElement('span');
      span.className = 'disabled';
      span.textContent = label;
      return span;
    }
    var anchor = link(urlFor({ q: state.q, page: page, exact: state.exact }), label);
    if (label === 'Previous') anchor.rel = 'prev';
    if (label === 'Next') anchor.rel = 'next';
    return anchor;
  }

  function renderError(error) {
    el.results.textContent = '';
    el.pager.hidden = true;
    el.correction.hidden = true;
    el.status.classList.add('is-error');
    if (error.status === 429) {
      var wait = parseInt(error.retryAfter || '', 10);
      el.status.textContent = 'Too many searches in a short time. Please wait'
        + (isFinite(wait) ? ' ' + wait + ' s' : ' a moment') + ' and try again.';
    } else if (error.status) {
      el.status.textContent = error.message;
    } else {
      el.status.textContent = 'Cannot reach the search server. Check your connection and try again.';
    }
  }

  // ------------------------------------------------------------------ autocomplete

  function requestSuggestions() {
    window.clearTimeout(suggestTimer);
    var value = el.input.value;
    if (!value.trim() || /\s$/.test(value)) {
      closeSuggestions();
      return;
    }
    suggestTimer = window.setTimeout(function () {
      if (suggestController) suggestController.abort();
      suggestController = new AbortController();
      fetch('/api/suggest?' + new URLSearchParams({ q: value }).toString(), { signal: suggestController.signal })
        .then(function (response) { return response.ok ? response.json() : { suggestions: [] }; })
        .then(function (data) {
          if (el.input.value === value && document.activeElement === el.input) {
            showSuggestions(value, data.suggestions || []);
          }
        })
        .catch(function () { /* suggestions are optional */ });
    }, SUGGEST_DELAY_MS);
  }

  function showSuggestions(typed, suggestions) {
    el.suggestions.textContent = '';
    activeSuggestion = -1;
    if (suggestions.length === 0) {
      closeSuggestions();
      return;
    }
    suggestions.forEach(function (suggestion, index) {
      var option = document.createElement('li');
      option.id = 'suggestion-' + index;
      option.setAttribute('role', 'option');
      option.setAttribute('aria-selected', 'false');
      option.dir = 'auto';
      var common = suggestion.toLowerCase().indexOf(typed.toLowerCase()) === 0 ? typed.length : 0;
      option.appendChild(document.createTextNode(suggestion.slice(0, common)));
      var rest = document.createElement('b');
      rest.textContent = suggestion.slice(common);
      option.appendChild(rest);
      option.addEventListener('mousedown', function (event) {
        event.preventDefault(); // keep focus in the input
        chooseSuggestion(suggestion);
      });
      el.suggestions.appendChild(option);
    });
    el.suggestions.hidden = false;
    el.input.setAttribute('aria-expanded', 'true');
  }

  function moveSuggestion(delta) {
    var options = el.suggestions.children;
    if (el.suggestions.hidden || options.length === 0) return false;
    if (activeSuggestion >= 0) options[activeSuggestion].setAttribute('aria-selected', 'false');
    // Cycle through "nothing selected" (-1) and each option: n + 1 states, stored shifted by one.
    var states = options.length + 1;
    activeSuggestion = ((activeSuggestion + 1 + delta) % states + states) % states - 1;
    if (activeSuggestion >= 0) {
      options[activeSuggestion].setAttribute('aria-selected', 'true');
      el.input.setAttribute('aria-activedescendant', options[activeSuggestion].id);
    } else {
      el.input.removeAttribute('aria-activedescendant');
    }
    return true;
  }

  function chooseSuggestion(text) {
    el.input.value = text;
    closeSuggestions();
    navigate({ q: text.trim(), page: 1, exact: false });
  }

  function closeSuggestions() {
    window.clearTimeout(suggestTimer);
    if (suggestController) suggestController.abort();
    el.suggestions.hidden = true;
    el.suggestions.textContent = '';
    activeSuggestion = -1;
    el.input.setAttribute('aria-expanded', 'false');
    el.input.removeAttribute('aria-activedescendant');
  }

  // ------------------------------------------------------------------ document viewer

  function openDocument(id) {
    el.viewerTitle.textContent = 'Loading…';
    el.viewerMeta.textContent = '';
    el.viewerBody.textContent = '';
    if (typeof el.viewer.showModal === 'function') {
      el.viewer.showModal();
    } else {
      el.viewer.setAttribute('open', '');
    }
    fetch('/api/documents/' + encodeURIComponent(id))
      .then(function (response) {
        if (!response.ok) throw new Error('HTTP ' + response.status);
        return response.json();
      })
      .then(function (doc) {
        el.viewerTitle.textContent = doc.title || doc.url || 'Untitled document';
        el.viewerMeta.textContent = doc.url || '';
        el.viewerBody.textContent = doc.body + (doc.truncated ? '\n\n[…]' : '');
      })
      .catch(function () {
        el.viewerTitle.textContent = 'This document could not be loaded.';
      });
  }

  function closeViewer() {
    if (typeof el.viewer.close === 'function') {
      el.viewer.close();
    } else {
      el.viewer.removeAttribute('open');
    }
  }

  // ------------------------------------------------------------------ helpers

  function link(href, text) {
    var anchor = document.createElement('a');
    anchor.href = href;
    anchor.textContent = text;
    anchor.addEventListener('click', function (event) {
      if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      var target = new URL(anchor.href, window.location.href);
      window.history.pushState(null, '', target.pathname + target.search);
      render(readState());
      window.scrollTo(0, 0);
      el.results.focus({ preventScroll: true });
    });
    return anchor;
  }

  /** The URL if it is an absolute http(s) URL, otherwise null (never javascript: or data:). */
  function httpUrl(url) {
    if (!url) return null;
    try {
      var parsed = new URL(url);
      return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.href : null;
    } catch (e) {
      return null;
    }
  }

  function displaySource(url) {
    var safe = httpUrl(url);
    if (!safe) return url || '';
    var parsed = new URL(safe);
    var path = decodeSafely(parsed.pathname).replace(/\/$/, '').split('/').filter(Boolean);
    return [parsed.host].concat(path).join(' › ');
  }

  function decodeSafely(text) {
    try {
      return decodeURIComponent(text);
    } catch (e) {
      return text;
    }
  }

  function formatMillis(ms) {
    if (typeof ms !== 'number') return '';
    return (ms < 1 ? ms.toFixed(2) : ms < 100 ? ms.toFixed(1) : Math.round(ms)) + ' ms';
  }

  function loadStats() {
    fetch('/api/stats')
      .then(function (response) { return response.ok ? response.json() : null; })
      .then(function (stats) {
        if (!stats) return;
        var docs = numberFormat.format(stats.documents) + (stats.documents === 1 ? ' document' : ' documents');
        el.footer.textContent = docs + ' indexed · updated ' + new Date(stats.indexedAt).toLocaleDateString();
      })
      .catch(function () { /* optional */ });
  }

  // ------------------------------------------------------------------ events

  el.form.addEventListener('submit', function (event) {
    event.preventDefault();
    var options = el.suggestions.children;
    if (activeSuggestion >= 0 && options[activeSuggestion]) {
      chooseSuggestion(options[activeSuggestion].textContent);
      return;
    }
    closeSuggestions();
    navigate({ q: el.input.value.trim(), page: 1, exact: false });
    el.input.blur();
  });

  el.input.addEventListener('input', requestSuggestions);
  el.input.addEventListener('keydown', function (event) {
    if (event.key === 'ArrowDown' && moveSuggestion(1)) {
      event.preventDefault();
    } else if (event.key === 'ArrowUp' && moveSuggestion(-1)) {
      event.preventDefault();
    } else if (event.key === 'Escape' && !el.suggestions.hidden) {
      event.preventDefault();
      closeSuggestions();
    }
  });
  el.input.addEventListener('blur', function () { window.setTimeout(closeSuggestions, 100); });

  document.addEventListener('keydown', function (event) {
    var target = event.target;
    var typing = target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable);
    if (event.key === '/' && !typing && !event.ctrlKey && !event.metaKey && !event.altKey && !el.viewer.open) {
      event.preventDefault();
      el.input.focus();
      el.input.select();
    }
  });

  el.viewerClose.addEventListener('click', closeViewer);
  el.viewer.addEventListener('click', function (event) {
    if (event.target === el.viewer) closeViewer(); // click on the backdrop
  });

  window.addEventListener('popstate', function () { render(readState()); });

  loadStats();
  render(readState());
  if (!readState().q) el.input.focus();
})();
