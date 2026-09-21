const q = (selector) => document.querySelector(selector);

const form = q("#cloneForm");
const targetUrl = q("#targetUrl");
const cloneBtn = q("#cloneBtn");
const activeJob = q("#activeJob");
const jobTitle = q("#jobTitle");
const jobUrl = q("#jobUrl");
const jobBadge = q("#jobBadge");
const jobStatusDot = q("#jobStatusDot");
const progressBar = q("#progressBar");
const metricFiles = q("#metricFiles");
const metricBytes = q("#metricBytes");
const metricFailed = q("#metricFailed");
const logOutput = q("#logOutput");
const resultActions = q("#resultActions");
const previewBtn = q("#previewBtn");
const downloadBtn = q("#downloadBtn");
const historyGrid = q("#historyGrid");

let pollTimer = null;

function formatBytes(bytes) {
  if (!bytes) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  let value = bytes;
  let index = 0;

  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }

  return value.toFixed(index ? 1 : 0) + " " + units[index];
}

function formatHost(url) {
  try {
    return new URL(url).hostname;
  } catch {
    return url;
  }
}

function setBusy(busy) {
  cloneBtn.disabled = busy;
  targetUrl.disabled = busy;
  cloneBtn.querySelector("span").textContent = busy ? "Cloning…" : "Clone frontend";
}

function renderJob(job) {
  activeJob.classList.remove("hidden");
  jobUrl.textContent = job.url;
  jobBadge.textContent = job.status.toUpperCase();
  metricFiles.textContent = job.files || 0;
  metricBytes.textContent = formatBytes(job.bytes || 0);
  metricFailed.textContent = job.failed || 0;

  if (job.log) {
    const nearBottom =
      logOutput.scrollHeight - logOutput.scrollTop - logOutput.clientHeight < 70;
    logOutput.textContent = job.log;
    if (nearBottom) logOutput.scrollTop = logOutput.scrollHeight;
  }

  jobStatusDot.className = "status-dot";
  progressBar.className = "";
  resultActions.classList.add("hidden");

  if (job.status === "done") {
    jobTitle.textContent = "Clone complete — " + formatHost(job.url);
    jobStatusDot.classList.add("done");
    progressBar.classList.add("done");
    resultActions.classList.remove("hidden");
    previewBtn.href = job.preview_url;
    downloadBtn.href = job.download_url;
    setBusy(false);
    stopPolling();
    loadHistory();
  } else if (job.status === "error") {
    jobTitle.textContent = "Clone failed";
    jobStatusDot.classList.add("error");
    progressBar.classList.add("error");

    if (job.error && !job.log.includes(job.error)) {
      logOutput.textContent += "\n[!] " + job.error;
    }

    setBusy(false);
    stopPolling();
    loadHistory();
  } else if (job.status === "running") {
    jobTitle.textContent = "Cloning " + formatHost(job.url) + "…";
    jobStatusDot.classList.add("running");
  } else {
    jobTitle.textContent = "Clone queued…";
    jobStatusDot.classList.add("running");
  }
}

async function fetchJob(id) {
  const response = await fetch("/api/jobs/" + id);
  if (!response.ok) throw new Error("Could not read clone status.");
  const data = await response.json();
  renderJob(data.job);
}

function startPolling(id) {
  stopPolling();
  pollTimer = setInterval(() => fetchJob(id).catch(() => {}), 900);
  fetchJob(id).catch(() => {});
}

function stopPolling() {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = null;
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const url = targetUrl.value.trim();
  if (!url) return;

  setBusy(true);
  resultActions.classList.add("hidden");
  activeJob.classList.remove("hidden");
  jobTitle.textContent = "Starting clone…";
  jobUrl.textContent = url;
  jobBadge.textContent = "QUEUED";
  metricFiles.textContent = "0";
  metricBytes.textContent = "0 B";
  metricFailed.textContent = "0";
  logOutput.textContent = "Handing target to SPA-Ripper worker…";

  try {
    const response = await fetch("/api/clone", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({url})
    });

    const data = await response.json();
    if (!response.ok) {
      throw new Error(data.error || "Could not start clone.");
    }

    renderJob(data.job);
    startPolling(data.job.id);
  } catch (error) {
    setBusy(false);
    jobTitle.textContent = "Could not start clone";
    jobBadge.textContent = "ERROR";
    jobStatusDot.className = "status-dot error";
    progressBar.className = "error";
    logOutput.textContent = "[!] " + error.message;
  }
});

q("#clearLog").addEventListener("click", () => {
  logOutput.textContent = "";
});

q("#refreshHistory").addEventListener("click", loadHistory);

async function loadHistory() {
  try {
    const response = await fetch("/api/jobs");
    const data = await response.json();
    const jobs = data.jobs || [];

    if (!jobs.length) {
      historyGrid.innerHTML =
        '<div class="empty-state">No clones yet in this server session.</div>';
      return;
    }

    historyGrid.innerHTML = jobs.slice(0, 9).map((job) => {
      const action =
        job.status === "done"
          ? '<a href="' + job.preview_url + '" target="_blank" rel="noreferrer">Preview ↗</a>'
          : "";

      return (
        '<article class="history-card">' +
          '<header><b>' + escapeHtml(formatHost(job.url)) + '</b>' +
          '<span class="state">' + job.status.toUpperCase() + '</span></header>' +
          '<p>' + escapeHtml(job.url) + '</p>' +
          '<footer>' +
            '<span>' + (job.files || 0) + ' files</span>' +
            '<span>•</span>' +
            '<span>' + formatBytes(job.bytes || 0) + '</span>' +
            action +
          '</footer>' +
        '</article>'
      );
    }).join("");
  } catch {
    historyGrid.innerHTML =
      '<div class="empty-state">Could not load session history.</div>';
  }
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

loadHistory();
