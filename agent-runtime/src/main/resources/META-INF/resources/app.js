const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];
const state = { key: '', local: false, view: 'build', mode: 'live', blueprint: null, run: null, incidents: [], poll: null, timerEnd: null, remaining: 900, modelReady: false };
const titles = { build: 'Build the blueprint', secure: 'Secure the path', execute: 'Let agents work', activity: 'Execution history' };
const escape = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
const time = (value) => new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
let toastTimeout;
function toast(message) { $('#toast').textContent = message; $('#toast').classList.remove('hidden'); clearTimeout(toastTimeout); toastTimeout = setTimeout(() => $('#toast').classList.add('hidden'), 6000); }
function access() { if (!$('#access-dialog').open) $('#access-dialog').showModal(); }
async function api(path, options = {}, retry = true) {
  if (!state.key) { access(); throw new Error('Connect with your presenter key first.'); }
  const response = await fetch(`/api${path}`, { ...options, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${state.key}` }, signal: AbortSignal.timeout(22000) });
  const text = await response.text();
  let data;
  try { data = text ? JSON.parse(text) : {}; } catch { data = { error: text.slice(0, 160) }; }
  if (!response.ok) {
    if (response.status === 401) {
      if (state.local && retry) {
        try { if (await localSession()) return api(path, options, false); } catch { /* Fall back to manual access. */ }
      }
      state.key = ''; state.local = false; access();
    }
    throw new Error(data.error || data.message || `Request failed (${response.status}). Check service logs.`);
  }
  return data;
}
async function action(button, operation) {
  button.disabled = true;
  try { await operation(); } catch (error) { toast(error.message); } finally { button.disabled = false; }
}
function showView(view) {
  state.view = view;
  $$('.view').forEach((element) => element.classList.toggle('hidden', element.id !== `view-${view}`));
  $$('[data-view]').forEach((element) => element.classList.toggle('active', element.dataset.view === view));
  $('#crumb').textContent = titles[view];
  if (state.key && view === 'secure') refreshAudit().catch((e) => toast(e.message));
  if (state.key && view === 'activity') refreshHistory().catch((e) => toast(e.message));
}
$$('[data-view]').forEach((button) => button.addEventListener('click', () => showView(button.dataset.view)));
$$('[data-next]').forEach((button) => button.addEventListener('click', () => showView(button.dataset.next)));
$('#settings').addEventListener('click', () => state.local ? initializeAccess() : access());
$('#close-access').addEventListener('click', () => $('#access-dialog').close());
$('#access-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  state.key = $('#access-key').value.trim();
  state.local = false;
  const button = event.submitter;
  button.disabled = true;
  $('#access-error').textContent = '';
  try {
    await connect();
    $('#access-key').value = '';
    $('#access-dialog').close();
    toast('Connected. Your flight deck is ready.');
  } catch (error) { $('#access-error').textContent = error.message; state.key = ''; }
  finally { button.disabled = false; }
});
async function connect() {
  const [status, incidents] = await Promise.all([api('/status'), api('/incidents')]);
  state.modelReady = status.modelReady;
  state.incidents = incidents;
  $('#incident').innerHTML = incidents.map((i) => `<option value="${escape(i.id)}">${escape(i.id)} · ${escape(i.service)}</option>`).join('');
  $('#gateway-label').textContent = status.gateway === 'Local policy simulator' ? 'Local simulator' : status.gateway;
  $('#connection').textContent = `Connected · PostgreSQL ready · ${status.model}`;
  $('#model-pill').textContent = status.modelReady ? status.model : 'MODEL NOT FOUND';
  $('#model-pill').classList.toggle('green', status.modelReady);
  $('#model-pill').classList.toggle('amber', !status.modelReady);
  incidentChanged();
  if (!status.modelReady) toast('Model availability could not be confirmed. Check the server API key and model settings, or use rehearsal mode.');
}
async function localSession() {
  const response = await fetch('/local-session', {
    method: 'POST', headers: { 'X-Runway-Local': '1' },
    credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(5000)
  });
  if (!response.ok) return false;
  const session = await response.json();
  if (typeof session.token !== 'string' || session.token.length < 24) return false;
  state.key = session.token;
  state.local = true;
  return true;
}
async function initializeAccess() {
  $('#connection').textContent = 'Connecting to your local demo…';
  try {
    if (await localSession()) {
      await connect();
      $('#access-dialog').close();
      return;
    }
  } catch (error) { $('#access-error').textContent = `Connection unavailable: ${error.message}`; }
  state.key = ''; state.local = false;
  $('#connection').textContent = 'Connect with your presenter key to begin';
  access();
}
function incidentChanged() {
  const incident = state.incidents.find((i) => i.id === $('#incident').value);
  if (!incident) return;
  $('#incident-title').textContent = incident.summary;
  $('#incident-severity').textContent = incident.severity;
}
$('#incident').addEventListener('change', incidentChanged);
$('#generate').addEventListener('click', (event) => action(event.currentTarget, async () => {
  if (!$('#brief').value.trim()) throw new Error('Add a mission brief first.');
  state.blueprint = await api('/blueprint', { method: 'POST', body: JSON.stringify({ prompt: $('#brief').value }) });
  $('#artifact-panel').classList.remove('hidden');
  showArtifact('topology');
  $('#node-bob').classList.add('ready');
  toast('Blueprint assembled. Copy the handoff prompt into IBM Bob.');
}));
const TOPO_ICONS = {
  'IBM Bob': { cls: 'bob-icon', glyph: '✦', sub: 'Design & generate' },
  'Quarkus agents': { cls: 'quarkus-icon', glyph: 'Q<span>✦</span>', sub: 'Investigate + review' },
  'IBM DataPower gateway': { cls: 'shield-icon', glyph: '◇<b>✓</b>', sub: 'Policy enforcement' },
  'Policy service': { cls: 'policy-icon', glyph: '❖', sub: 'Allowlist + validation' },
  'MCP tools': { cls: 'tools-icon', glyph: '⌘', sub: 'Bounded capabilities' },
  'PostgreSQL': { cls: 'db-icon', glyph: '▤', sub: 'Seeded records' },
};
const TOPO_LINKS = { 'Quarkus agents→IBM DataPower gateway': 'MCP', 'IBM DataPower gateway→Policy service': 'reverse-proxy', 'Policy service→MCP tools': 'allowlisted', 'MCP tools→PostgreSQL': 'SQL' };
function renderTopology(topology) {
  const nodes = topology.nodes || [];
  const flow = nodes.map((name, i) => {
    const meta = TOPO_ICONS[name] || { cls: '', glyph: '◦', sub: '' };
    const node = `<div class="topology-node"><span class="node-icon ${meta.cls}">${meta.glyph}</span><strong>${escape(name)}</strong><small>${escape(meta.sub)}</small></div>`;
    if (i === nodes.length - 1) return node;
    const generation = i === 0;
    const label = generation ? 'generates' : (TOPO_LINKS[`${name}→${nodes[i + 1]}`] || 'MCP');
    return `${node}<div class="connector${generation ? ' generation' : ''}"><span>${escape(label)}</span><i></i></div>`;
  }).join('');
  return `<div class="topology-heading"><span><span class="live-dot"></span> RUNTIME REQUEST PATH</span><span>${escape(topology.transport || '')}</span></div>`
    + `<div class="topology-flow">${flow}</div>`
    + `<div class="topology-footer"><span>↳ IBM Bob generates the code; runtime requests flow agents → DataPower → policy → tools → data.</span><span class="protocol">WRITE: ${escape((topology.writePolicy || '').toUpperCase())}</span></div>`;
}
function showArtifact(kind) {
  if (!state.blueprint) return;
  $$('[data-artifact]').forEach((button) => button.classList.toggle('active', button.dataset.artifact === kind));
  const visual = $('#artifact-visual');
  if (kind === 'topology') {
    visual.innerHTML = renderTopology(state.blueprint.topology);
    visual.classList.remove('hidden');
    $('#artifact-code').classList.add('hidden');
    $('#copy-artifact').classList.add('hidden');
    return;
  }
  visual.classList.add('hidden');
  $('#artifact-code').classList.remove('hidden');
  $('#copy-artifact').classList.remove('hidden');
  $('#artifact-code').textContent = ({ java: state.blueprint.files['InvestigatorAgent.java'], config: state.blueprint.files['application.properties'], bob: state.blueprint.bobPrompt })[kind];
}
$$('[data-artifact]').forEach((button) => button.addEventListener('click', () => showArtifact(button.dataset.artifact)));
$('#copy-bob').addEventListener('click', (event) => action(event.currentTarget, async () => { await navigator.clipboard.writeText(state.blueprint.bobPrompt); toast('IBM Bob prompt copied.'); }));
$('#copy-artifact').addEventListener('click', (event) => action(event.currentTarget, async () => { await navigator.clipboard.writeText($('#artifact-code').textContent); toast('Copied to clipboard.'); }));
$('#download').addEventListener('click', () => {
  const url = URL.createObjectURL(new Blob([JSON.stringify(state.blueprint, null, 2)], { type: 'application/json' }));
  const link = document.createElement('a'); link.href = url; link.download = 'enterprise-ai-blueprint.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
});
$$('[data-probe]').forEach((button) => button.addEventListener('click', () => action(button, async () => {
  const reply = await api(`/probes/${button.dataset.probe}`, { method: 'POST' });
  const expected = { unauthorized: 401, 'forbidden-tool': 403, 'invalid-arguments': 400 }[button.dataset.probe];
  const result = $('#probe-result');
  result.classList.remove('hidden'); result.classList.toggle('failure', reply.status !== expected);
  result.textContent = `${reply.status === expected ? '✓ Policy verified' : 'Unexpected result'} · HTTP ${reply.status} · ${reply.body.error || 'See gateway logs'} · Request ${reply.requestId}`;
  await refreshAudit();
})));
async function refreshAudit() {
  const rows = await api('/audit');
  $('#audit-body').innerHTML = rows.length ? rows.map((row) => `<tr title="Request ${escape(row.request_id)}"><td>${escape(time(row.created_at))}</td><td>${escape(row.principal)}</td><td>${escape(row.tool || row.method)}</td><td><span class="pill ${row.decision === 'ALLOW' ? 'green' : 'red'}">${escape(row.decision)} · ${row.status}</span></td><td>${escape(row.reason)}</td></tr>`).join('') : '<tr><td colspan="5" class="empty">No gateway requests yet. Try a security probe.</td></tr>';
}
$('#refresh-audit').addEventListener('click', (event) => action(event.currentTarget, refreshAudit));
$$('[data-mode]').forEach((button) => button.addEventListener('click', () => {
  state.mode = button.dataset.mode;
  $$('[data-mode]').forEach((b) => b.classList.toggle('active', b === button));
  $('#mode-note').textContent = state.mode === 'live' ? 'Two LangChain4j AI services investigate and review. The model selects read tools through MCP.' : 'No LLM calls. A deterministic workflow exercises the real gateway, MCP tools and database.';
}));
$('#run').addEventListener('click', (event) => action(event.currentTarget, startRun));
async function startRun() {
  if (state.run?.status === 'RUNNING') throw new Error('An investigation is already in progress in this tab.');
  if (!$('#mission').value.trim()) throw new Error('Enter an investigation request.');
  const response = await api('/runs', { method: 'POST', body: JSON.stringify({ incidentId: $('#incident').value, mode: state.mode, prompt: $('#mission').value }) });
  clearTimeout(state.poll);
  $('#report-panel').classList.add('hidden');
  $('#approval-result').classList.add('hidden');
  state.run = { id: response.id, status: 'RUNNING' };
  await pollRun(response.id);
}
async function pollRun(id) {
  try {
    const run = await api(`/runs/${id}`);
    if (state.run && state.run.id !== id) return;
    renderRun(run);
    if (run.status === 'RUNNING' || run.status === 'APPROVING') state.poll = setTimeout(() => pollRun(id), 1200);
  } catch (error) {
    toast(`${error.message} Your persisted run can be reopened from history.`);
    $('.topology').classList.remove('running');
  }
}
function renderRun(run) {
  state.run = run;
  const running = run.status === 'RUNNING';
  $('.topology').classList.toggle('running', running);
  $('#run-status').textContent = run.status.replaceAll('_', ' ');
  $('#run-status').className = `pill ${run.status === 'FAILED' ? 'red' : run.status === 'APPROVED' ? 'green' : 'amber'}`;
  $('#run-id').textContent = `RUN ${run.id} · ${run.mode.toUpperCase()} · ${run.incident_id}`;
  $('#trace').innerHTML = (run.events || []).map((event) => `<div class="trace-item"><div><strong>${escape(event.actor)}</strong><time>${escape(time(event.at))}</time></div><p>${escape(event.detail)}</p></div>`).join('') || '<p class="muted">Starting the workflow…</p>';
  if (run.report) {
    $('#report-panel').classList.remove('hidden');
    $('#report').textContent = run.report;
    $('#report-mode').textContent = run.mode === 'live' ? 'LIVE AI REPORT' : 'REHEARSAL · NO LLM';
    $('#approval').classList.toggle('hidden', run.status !== 'AWAITING_APPROVAL');
    $('#approval-result').classList.toggle('hidden', !['APPROVED', 'REJECTED', 'APPROVING'].includes(run.status));
    if (run.status === 'APPROVED') $('#approval-result').textContent = '✓ Approved. One follow-up task is recorded for this run. Repeated approval cannot create duplicates.';
    if (run.status === 'REJECTED') $('#approval-result').textContent = 'Declined. No follow-up task was created.';
    if (run.status === 'APPROVING') $('#approval-result').textContent = 'Recording your decision and creating the follow-up…';
  }
}
$('#approve').addEventListener('click', (event) => action(event.currentTarget, async () => {
  $('#reject').disabled = true;
  try {
    const task = await api(`/runs/${state.run.id}/approve`, { method: 'POST' });
    await pollRun(state.run.id);
    $('#approval-result').textContent = `✓ Follow-up ${task.id} created for ${task.incident_id}. No infrastructure was changed.`;
    toast('Human decision recorded. Follow-up created.');
  } finally { $('#reject').disabled = false; }
}));
$('#reject').addEventListener('click', (event) => action(event.currentTarget, async () => {
  await api(`/runs/${state.run.id}/reject`, { method: 'POST' }); await pollRun(state.run.id); toast('Recommendation declined. No task created.');
}));
async function refreshHistory() {
  const rows = await api('/runs');
  $('#history-body').innerHTML = rows.length ? rows.map((run) => `<tr><td>${escape(new Date(run.created_at).toLocaleString())}</td><td>${escape(run.incident_id)}</td><td>${escape(run.mode)}</td><td><span class="pill ${run.status === 'APPROVED' ? 'green' : ''}">${escape(run.status.replaceAll('_', ' '))}</span></td><td><button class="text-button" data-open-run="${escape(run.id)}">Open run ↗</button></td></tr>`).join('') : '<tr><td colspan="5" class="empty">Your first investigation is one mission away.</td></tr>';
  $$('[data-open-run]').forEach((button) => button.addEventListener('click', () => action(button, async () => {
    clearTimeout(state.poll); state.run = { id: button.dataset.openRun }; showView('execute');
    $('#report-panel').classList.add('hidden'); $('#approval-result').classList.add('hidden');
    await pollRun(button.dataset.openRun);
  })));
}
$('#refresh-history').addEventListener('click', (event) => action(event.currentTarget, refreshHistory));
$('#timer-toggle').addEventListener('click', () => {
  if (state.timerEnd) { state.remaining = Math.max(0, Math.ceil((state.timerEnd - Date.now()) / 1000)); state.timerEnd = null; }
  else { if (!state.remaining) state.remaining = 900; state.timerEnd = Date.now() + state.remaining * 1000; }
  $('#timer-toggle').textContent = state.timerEnd ? 'Ⅱ' : '▶';
  $('#timer-caption').textContent = state.timerEnd ? 'Build 4 min · Secure 4 min · Execute 7 min' : 'Presenter timer paused. Take your time.';
});
setInterval(() => {
  if (!state.timerEnd) return;
  const remaining = Math.max(0, Math.ceil((state.timerEnd - Date.now()) / 1000));
  $('#timer').textContent = `${String(Math.floor(remaining / 60)).padStart(2, '0')}:${String(remaining % 60).padStart(2, '0')}`;
  $('#timer-progress').style.width = `${((900 - remaining) / 900) * 100}%`;
  if (remaining === 0) { state.timerEnd = null; state.remaining = 0; $('#timer-toggle').textContent = '↻'; $('#timer-caption').textContent = 'Time for the takeaway. Your demo stays available.'; }
}, 500);
document.addEventListener('keydown', (event) => {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter' && state.view === 'execute' && !$('#access-dialog').open) { event.preventDefault(); $('#run').click(); }
});
initializeAccess();
