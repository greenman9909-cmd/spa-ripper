'use strict';

// ── DOM refs ──────────────────────────────────
const $ = (sel) => document.querySelector(sel);
const form           = $('#cloneForm');
const targetUrl      = $('#targetUrl');
const cloneBtn       = $('#cloneBtn');
const btnMountLabel  = cloneBtn.querySelector('.btn-mount-label');
const btnMountArrow  = cloneBtn.querySelector('.btn-mount-arrow');
const btnMountSpinner= cloneBtn.querySelector('.btn-mount-spinner');
const activeJob      = $('#activeJob');
const jobDot         = $('#jobDot');          // the embed-tag-badge showing status text
const jobTitle       = $('#jobTitle');
const jobUrl         = $('#jobUrl');
const progressBar    = $('#progressBar');
const metricFiles    = $('#metricFiles');
const metricBytes    = $('#metricBytes');
const metricFailed   = $('#metricFailed');
const metricElapsed  = $('#metricElapsed');
const logOutput      = $('#logOutput');
const resultActions  = $('#resultActions');
const previewBtn     = $('#previewBtn');
const downloadBtn    = $('#downloadBtn');
const historyGrid    = $('#historyGrid');
const stackDetect    = $('#stackDetect');
const stackBadges    = $('#stackBadges');

// ── State ─────────────────────────────────────
let pollTimer    = null;
let elapsedTimer = null;
let jobStartTs   = null;

// ── Helpers ───────────────────────────────────
function formatBytes(b) {
  if (!b) return '0 B';
  const u = ['B','KB','MB','GB'];
  let v = b, i = 0;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(i ? 1 : 0)} ${u[i]}`;
}

function formatHost(url) {
  try { return new URL(url).hostname; } catch { return url; }
}

function formatElapsed(ts) {
  if (!ts) return '0s';
  const s = Math.floor((Date.now() - ts) / 1000);
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`;
}

function escapeHtml(v) {
  return String(v)
    .replaceAll('&','&amp;').replaceAll('<','&lt;')
    .replaceAll('>','&gt;').replaceAll('"','&quot;')
    .replaceAll("'",'&#039;');
}

function detectStack(log) {
  return Object.entries({
    React:   /\breact\b/i,
    Vite:    /vite|\.vite\//i,
    Vue:     /\bvue\b/i,
    Svelte:  /svelte/i,
    Angular: /angular/i,
    Next:    /next\.js|_next\//i,
    Nuxt:    /nuxt/i,
    TanStack:/tanstack|query-/i,
    Framer:  /framer|motion-/i,
    PWA:     /sw\.js|service.?worker|\.webmanifest/i,
  }).filter(([,re]) => re.test(log)).map(([n]) => n);
}

// ── Busy state ────────────────────────────────
function setBusy(busy) {
  cloneBtn.disabled     = busy;
  targetUrl.disabled    = busy;
  btnMountLabel.textContent = busy ? 'Cloning…' : 'Clone frontend';
  btnMountArrow.hidden  = busy;
  btnMountSpinner.hidden = !busy;
}

// ── Job dot badge ─────────────────────────────
function setDotBadge(status) {
  jobDot.className = 'embed-tag-badge job-dot-badge';
  const map = { queued: 'QUEUED', running: 'RUNNING', done: 'DONE', error: 'ERROR' };
  jobDot.textContent = map[status] ?? status.toUpperCase();
  if (status === 'running') jobDot.classList.add('running');
  else if (status === 'done') jobDot.classList.add('done');
  else if (status === 'error') jobDot.classList.add('error');
}

// ── Render job ────────────────────────────────
function renderJob(job) {
  activeJob.hidden = false;

  jobUrl.textContent = job.url;
  setDotBadge(job.status);

  metricFiles.textContent   = (job.files  || 0).toLocaleString();
  metricBytes.textContent   = formatBytes(job.bytes  || 0);
  metricFailed.textContent  = (job.failed || 0).toLocaleString();

  // Log
  if (job.log) {
    const atBottom = logOutput.scrollHeight - logOutput.scrollTop - logOutput.clientHeight < 80;
    logOutput.textContent = job.log;
    if (atBottom) logOutput.scrollTop = logOutput.scrollHeight;
  }

  // Stack detection
  if (job.log) {
    const found = detectStack(job.log);
    if (found.length) {
      stackBadges.innerHTML = found.map(s => `<span class="stack-chip">${escapeHtml(s)}</span>`).join('');
      stackDetect.hidden = false;
    }
  }

  resultActions.hidden = true;

  if (job.status === 'running' || job.status === 'queued') {
    jobTitle.textContent = job.status === 'running'
      ? `Cloning ${formatHost(job.url)}…`
      : 'Queued for processing…';
    progressBar.className = 'progress-fill indeterminate';

  } else if (job.status === 'done') {
    jobTitle.textContent = `Complete — ${formatHost(job.url)}`;
    progressBar.className = 'progress-fill done';
    resultActions.hidden = false;
    previewBtn.href  = job.preview_url;
    downloadBtn.href = job.download_url;
    metricElapsed.textContent = jobStartTs ? formatElapsed(jobStartTs) : '—';
    setBusy(false);
    stopPolling();
    stopElapsed();
    loadHistory();

  } else if (job.status === 'error') {
    jobTitle.textContent = 'Clone failed';
    progressBar.className = 'progress-fill error';
    if (job.error && !(job.log || '').includes(job.error)) {
      logOutput.textContent += `\n[!] ${job.error}`;
    }
    setBusy(false);
    stopPolling();
    stopElapsed();
    loadHistory();
  }
}

// ── Polling ───────────────────────────────────
async function fetchJob(id) {
  const res = await fetch(`/api/jobs/${id}`);
  if (!res.ok) throw new Error('Status fetch failed');
  renderJob((await res.json()).job);
}

function startPolling(id) {
  stopPolling();
  fetchJob(id).catch(() => {});
  pollTimer = setInterval(() => fetchJob(id).catch(() => {}), 900);
}
function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
}

// ── Elapsed ───────────────────────────────────
function startElapsed() {
  stopElapsed();
  jobStartTs = Date.now();
  elapsedTimer = setInterval(() => {
    metricElapsed.textContent = formatElapsed(jobStartTs);
  }, 1000);
}
function stopElapsed() {
  if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null; }
}

// ── Form submit ───────────────────────────────
form.addEventListener('submit', async (e) => {
  e.preventDefault();
  const url = targetUrl.value.trim();
  if (!url) return;

  setBusy(true);
  stackDetect.hidden = true;

  // Optimistic UI
  activeJob.hidden = false;
  setDotBadge('queued');
  jobTitle.textContent = 'Starting clone…';
  jobUrl.textContent = url;
  progressBar.className = 'progress-fill indeterminate';
  metricFiles.textContent  = '0';
  metricBytes.textContent  = '0 B';
  metricFailed.textContent = '0';
  metricElapsed.textContent = '0s';
  logOutput.textContent = 'Handing target to SPA-Ripper engine…';
  resultActions.hidden = true;

  startElapsed();

  try {
    const res  = await fetch('/api/clone', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'Could not start clone.');
    renderJob(data.job);
    startPolling(data.job.id);
  } catch (err) {
    setBusy(false);
    stopElapsed();
    setDotBadge('error');
    jobTitle.textContent = 'Failed to start clone';
    progressBar.className = 'progress-fill error';
    logOutput.textContent = `[!] ${err.message}`;
  }
});

// ── Clear log ─────────────────────────────────
$('#clearLog').addEventListener('click', () => { logOutput.textContent = ''; });

// ── History ───────────────────────────────────
$('#refreshHistory').addEventListener('click', loadHistory);

function historyStateClass(s) {
  return { done:'done', running:'running', error:'error', queued:'queued' }[s] ?? 'queued';
}

async function loadHistory() {
  try {
    const data = await fetch('/api/jobs').then(r => r.json());
    const jobs = (data.jobs || []).slice(0, 6);

    if (!jobs.length) {
      historyGrid.innerHTML = `
        <div class="history-empty-state">
          <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <rect x="2" y="3" width="20" height="14" rx="2"/>
            <line x1="8" y1="21" x2="16" y2="21"/>
            <line x1="12" y1="17" x2="12" y2="21"/>
          </svg>
          <span>No clones yet this session.</span>
        </div>`;
      return;
    }

    historyGrid.innerHTML = jobs.map(job => {
      const host  = escapeHtml(formatHost(job.url));
      const stCls = historyStateClass(job.status);
      const previewLink = job.status === 'done'
        ? `<a class="action-mount-cta" href="${escapeHtml(job.preview_url)}" target="_blank" rel="noreferrer">Preview <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12h14M12 5l7 7-7 7"/></svg></a>`
        : '';
      return `
        <div class="anime-card">
          <div class="card-manifest-head">
            <span class="manifest-tag">${escapeHtml(host)}</span>
            <span class="manifest-status">
              <span class="manifest-status-dot" style="${job.status === 'error' ? 'background:#f87171' : ''}"></span>
              ${job.status.toUpperCase()}
            </span>
          </div>
          <div class="card-meta-body">
            <div>
              <div class="card-title-line">${escapeHtml(host)}</div>
              <div class="card-studio-sub" style="font-family:var(--mono);font-size:10.5px;word-break:break-all;">${escapeHtml(job.url)}</div>
              <div class="card-specs-group">
                <span class="spec-chip">${(job.files||0).toLocaleString()} files</span>
                <span class="spec-chip">${formatBytes(job.bytes||0)}</span>
                ${job.failed ? `<span class="spec-chip" style="color:#f87171">${job.failed} failed</span>` : ''}
              </div>
            </div>
            <div class="card-action-row">
              <span class="action-node-id">${job.status}</span>
              ${previewLink}
            </div>
          </div>
        </div>`;
    }).join('');

  } catch {
    historyGrid.innerHTML = `<div class="history-empty-state"><span>Could not load history.</span></div>`;
  }
}

// ── Init ──────────────────────────────────────
loadHistory();
targetUrl.focus();
