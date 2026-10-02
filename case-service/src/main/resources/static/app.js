'use strict';

(() => {
  const state = { auth: sessionStorage.getItem('mrd.auth'), me: null, page: 0, size: 20, selected: null, cy: null };
  const $ = (id) => document.getElementById(id);
  const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

  function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (v === undefined || v === null || v === false) continue;
      if (k === 'class') node.className = v;
      else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
      else node.setAttribute(k, v === true ? '' : v);
    }
    for (const c of children.flat()) {
      if (c === null || c === undefined) continue;
      node.append(c instanceof Node ? c : document.createTextNode(String(c)));
    }
    return node;
  }

  function showMessage(text, kind = 'error') {
    const m = $('message');
    m.textContent = text;
    m.className = 'message' + (kind === 'info' ? ' info' : '');
    clearTimeout(showMessage.t);
    showMessage.t = setTimeout(() => m.classList.add('hidden'), 6000);
  }

  async function api(path, options = {}) {
    const headers = Object.assign({ 'X-Requested-With': 'fetch', Authorization: 'Basic ' + state.auth }, options.headers || {});
    if (options.body && typeof options.body !== 'string') {
      headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(options.body);
    }
    const res = await fetch(path, Object.assign({}, options, { headers }));
    if (res.status === 401) {
      logout();
      throw new Error('Sign-in failed or session expired.');
    }
    if (!res.ok) {
      let detail = res.status + ' ' + res.statusText;
      try {
        const body = await res.json();
        detail = body.detail || body.title || detail;
        if (body.errors) detail += ': ' + body.errors.join('; ');
      } catch (_) { /* non-JSON error */ }
      throw new Error(detail);
    }
    return res;
  }

  const json = async (path, options) => (await api(path, options)).json();

  async function login(event) {
    event.preventDefault();
    state.auth = btoa($('login-user').value + ':' + $('login-pass').value);
    try {
      state.me = await json('/api/me');
      sessionStorage.setItem('mrd.auth', state.auth);
      $('login-pass').value = '';
      enterApp();
    } catch (e) {
      state.auth = null;
      showMessage(e.message.includes('403') || e.message.includes('Forbidden')
        ? 'This account cannot use the case desk.' : e.message);
    }
  }

  function logout() {
    sessionStorage.removeItem('mrd.auth');
    state.auth = null;
    state.me = null;
    $('app').classList.add('hidden');
    $('session').classList.add('hidden');
    $('login-form').classList.remove('hidden');
  }

  function enterApp() {
    $('login-form').classList.add('hidden');
    $('session').classList.remove('hidden');
    $('session-user').textContent = state.me.fullName + ' (' + state.me.username + ', ' + state.me.roles.join('/') + ')';
    $('app').classList.remove('hidden');
    loadCases();
  }

  async function loadCases() {
    const filter = $('status-filter').value;
    const params = new URLSearchParams({ page: state.page, size: state.size, sort: 'updatedAt,desc' });
    if (filter === '') ['OPEN', 'INVESTIGATING', 'ESCALATED'].forEach((s) => params.append('status', s));
    else if (filter !== 'ALL') params.append('status', filter);
    try {
      const page = await json('/api/cases?' + params);
      const rows = $('case-rows');
      rows.replaceChildren(...page.content.map((c) => el('tr', {
        class: c.id === state.selected ? 'selected' : null, 'data-id': c.id, onclick: () => selectCase(c.id),
      },
      el('td', { class: 'mono' }, c.reference),
      el('td', {}, el('span', { class: 'badge ' + c.status }, c.status)),
      el('td', {}, el('span', { class: 'badge ' + c.priority }, c.priority)),
      el('td', { class: 'num' }, c.alertCount),
      el('td', { class: 'num' }, c.accountCount),
      el('td', { class: 'num' }, usd.format(c.totalAmountUsd)),
      el('td', {}, c.assignee || '-'))));
      if (!page.content.length) rows.append(el('tr', {}, el('td', { colspan: 7, class: 'empty' }, 'No cases.')));
      $('page-info').textContent = page.totalElements ? `Page ${page.page + 1} of ${page.totalPages} - ${page.totalElements} cases` : '';
      $('prev').disabled = page.page <= 0;
      $('next').disabled = page.page + 1 >= page.totalPages;
    } catch (e) {
      showMessage(e.message);
    }
  }

  async function selectCase(id) {
    state.selected = id;
    document.querySelectorAll('#case-rows tr').forEach((tr) => tr.classList.toggle('selected', Number(tr.dataset.id) === id));
    try {
      const [detail, audit, graph] = await Promise.all([
        json(`/api/cases/${id}`), json(`/api/cases/${id}/audit`), json(`/api/cases/${id}/graph`)]);
      renderDetail(detail, audit, graph);
    } catch (e) {
      showMessage(e.message);
    }
  }

  function renderDetail(c, audit, graph) {
    const pane = $('detail');
    const frag = $('detail-template').content.cloneNode(true);
    const set = (f, v) => { const n = frag.querySelector(`[data-f="${f}"]`); n.textContent = v; if (n.classList.contains('badge')) n.classList.add(v); };
    set('reference', c.reference);
    set('status', c.status);
    set('priority', c.priority);
    set('disposition', c.disposition || '');
    set('maxScore', c.maxScore.toFixed(3));
    set('alertCount', c.alertCount);
    set('accountCount', c.accounts.length);
    set('totalAmountUsd', usd.format(c.totalAmountUsd));
    set('assignee', c.assignee || 'unassigned');
    set('ringIds', c.ringIds.join(', ') || '-');

    const commentForm = frag.querySelector('[data-slot="comment"]');
    const note = () => commentForm.elements.text.value.trim() || null;
    const after = async (promise, okText) => {
      try { await promise; showMessage(okText, 'info'); commentForm.reset(); await loadCases(); await selectCase(c.id); }
      catch (e) { showMessage(e.message); }
    };

    const transitions = frag.querySelector('[data-slot="transitions"]');
    const active = ['OPEN', 'INVESTIGATING', 'ESCALATED'].includes(c.status);
    if (active && c.assignee !== state.me.username) {
      transitions.append(el('button', { type: 'button', class: 'ghost', onclick: () => after(
        json(`/api/cases/${c.id}/assign`, { method: 'POST', body: { assignee: state.me.username } }), 'Assigned to you.') }, 'Assign to me'));
    }
    const pending = c.filingRequest && c.filingRequest.status === 'PENDING';
    for (const to of c.allowedTransitions) {
      if (pending) break;
      if (to === 'CLOSED') {
        const sel = el('select', {}, el('option', { value: 'NO_FURTHER_ACTION' }, 'No further action'),
          el('option', { value: 'FALSE_POSITIVE' }, 'False positive'));
        transitions.append(sel, el('button', { type: 'button', class: 'danger', onclick: () => after(
          json(`/api/cases/${c.id}/transition`, { method: 'POST', body: { to, disposition: sel.value, comment: note() } }),
          'Case closed.') }, 'Close'));
      } else {
        const label = to === 'INVESTIGATING' && c.status === 'ESCALATED' ? 'Send back' : to === 'INVESTIGATING' ? 'Start investigation' : 'Escalate';
        transitions.append(el('button', { type: 'button', onclick: () => after(
          json(`/api/cases/${c.id}/transition`, { method: 'POST', body: { to, comment: note() } }), `Moved to ${to}.`) }, label));
      }
    }

    const filing = frag.querySelector('[data-slot="filing"]');
    const hasStr = c.reports.some((r) => r.type === 'STR_XML');
    if (c.status === 'ESCALATED' && !pending) {
      filing.append(el('button', { type: 'button', disabled: !hasStr, onclick: () => after(
        json(`/api/cases/${c.id}/filing-request`, { method: 'POST', body: { comment: note() } }), 'Filing requested; awaiting supervisor approval.') },
      'Request STR filing'));
      if (!hasStr) filing.append(el('span', { class: 'note' }, 'Generate the STR XML first.'));
    }
    if (pending) {
      const fr = c.filingRequest;
      filing.append(el('span', { class: 'note' }, `Filing requested by ${fr.requestedBy} - awaiting a different supervisor.`));
      const isSupervisor = state.me.roles.includes('SUPERVISOR');
      if (isSupervisor && fr.requestedBy !== state.me.username) {
        filing.append(
          el('button', { type: 'button', onclick: () => after(
            json(`/api/cases/${c.id}/filing-approval`, { method: 'POST', body: { comment: note() } }), 'Filing approved; case closed as STR_FILED.') }, 'Approve filing'),
          el('button', { type: 'button', class: 'danger', onclick: () => {
            const reason = note();
            if (!reason) { showMessage('Enter the rejection reason in the comment box.'); return; }
            after(json(`/api/cases/${c.id}/filing-rejection`, { method: 'POST', body: { reason } }), 'Filing rejected; case sent back.');
          } }, 'Reject filing'));
      }
    }

    commentForm.addEventListener('submit', (e) => {
      e.preventDefault();
      const text = note();
      if (text) after(json(`/api/cases/${c.id}/comments`, { method: 'POST', body: { text } }), 'Comment added.');
    });

    frag.querySelector('[data-act="str"]').addEventListener('click', () => openBlob(`/api/cases/${c.id}/str.xml`, `${c.reference}-str.xml`, true, c.id));
    frag.querySelector('[data-act="html"]').addEventListener('click', () => openBlob(`/api/cases/${c.id}/summary.html`));
    frag.querySelector('[data-act="pdf"]').addEventListener('click', () => openBlob(`/api/cases/${c.id}/summary.pdf`, null, false, c.id));

    frag.querySelector('[data-slot="alerts"]').append(...c.alerts.map((a) => el('tr', {},
      el('td', {}, a.timestamp.replace('T', ' ').replace('Z', '')),
      el('td', { class: 'mono' }, a.alertId),
      el('td', { class: 'mono' }, a.fromAccount),
      el('td', { class: 'mono' }, a.toAccount),
      el('td', { class: 'num' }, usd.format(a.amountUsd)),
      el('td', { class: 'num' }, a.score.toFixed(3)),
      el('td', {}, a.detectors.join(', ')))));

    frag.querySelector('[data-slot="audit"]').append(...audit.map((e) => el('li', {},
      el('span', { class: 'when' }, e.timestamp.replace('T', ' ').slice(0, 19)),
      el('span', {}, e.actor),
      el('span', { class: 'what' }, e.action + (e.fromStatus && e.toStatus && e.fromStatus !== e.toStatus ? ` ${e.fromStatus} > ${e.toStatus}` : '')),
      el('span', { class: 'details' }, e.details || ''))));

    pane.replaceChildren(frag);
    renderGraph(pane.querySelector('[data-slot="graph"]'), graph);
  }

  async function openBlob(path, downloadName, refreshAfter, caseId) {
    try {
      const res = await api(path);
      const url = URL.createObjectURL(await res.blob());
      if (downloadName) {
        el('a', { href: url, download: downloadName }).click();
      } else {
        window.open(url, '_blank', 'noopener');
      }
      setTimeout(() => URL.revokeObjectURL(url), 60000);
      if (refreshAfter || caseId) await selectCase(caseId || state.selected);
    } catch (e) {
      showMessage(e.message);
    }
  }

  function renderGraph(container, graph) {
    if (state.cy) { state.cy.destroy(); state.cy = null; }
    if (!window.cytoscape) {
      container.replaceChildren(el('p', { class: 'empty' }, 'Graph library unavailable (offline?).'));
      return;
    }
    const colours = { source: '#c0392b', intermediary: '#d68910', sink: '#1f618d', member: '#7f8c8d' };
    state.cy = window.cytoscape({
      container,
      elements: [
        ...graph.nodes.map((n) => ({ data: { id: n.id, label: n.label, colour: colours[n.role] || '#7f8c8d', border: n.alerted ? 2 : 0 } })),
        ...graph.edges.map((e, i) => ({ data: {
          id: 'e' + i, source: e.source, target: e.target, alerted: e.alerted ? 1 : 0,
          label: (e.amountUsd != null ? usd.format(e.amountUsd) : '') + ' ' + e.detector,
        } })),
      ],
      style: [
        { selector: 'node', style: { 'background-color': 'data(colour)', label: 'data(label)', 'font-size': 8, color: '#2c3e50',
          width: 16, height: 16, 'border-width': 'data(border)', 'border-color': '#000', 'text-valign': 'bottom', 'text-margin-y': 3 } },
        { selector: 'edge', style: { width: 1.2, 'line-color': '#aab7b8', 'target-arrow-color': '#aab7b8', 'target-arrow-shape': 'triangle',
          'curve-style': 'bezier', 'arrow-scale': 0.8 } },
        { selector: 'edge[alerted = 1]', style: { width: 2.4, 'line-color': '#c0392b', 'target-arrow-color': '#c0392b' } },
        { selector: 'edge:selected', style: { label: 'data(label)', 'font-size': 8, 'text-background-color': '#fff', 'text-background-opacity': 1 } },
      ],
      layout: { name: graph.nodes.length > 40 ? 'concentric' : 'cose', animate: false, padding: 20 },
      wheelSensitivity: 0.2,
    });
  }

  document.addEventListener('DOMContentLoaded', async () => {
    $('login-form').addEventListener('submit', login);
    $('logout').addEventListener('click', logout);
    $('refresh').addEventListener('click', loadCases);
    $('status-filter').addEventListener('change', () => { state.page = 0; loadCases(); });
    $('prev').addEventListener('click', () => { state.page = Math.max(0, state.page - 1); loadCases(); });
    $('next').addEventListener('click', () => { state.page += 1; loadCases(); });
    if (state.auth) {
      try { state.me = await json('/api/me'); enterApp(); } catch (_) { logout(); }
    }
  });
})();
