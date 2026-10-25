import {
  makePreview,
  qualityScenario,
  exportCsv,
  exportMetadata,
  pathFor,
  validateMarker,
  inspectWindow,
  validateSessionName,
  findSessions,
} from "./domain.mjs";
import { LocalSessionRepository } from "./storage.mjs";
const DATA = __BIO_SIGNAL_PREVIEW_DATA__;
const icons = {
  wave: '<path d="M2 12h5l3-8 4 16 3-8h5"/>',
  monitor:
    '<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8m-4-4v4M5 11h4l2-4 3 7 2-3h3"/>',
  sessions:
    '<rect x="4" y="4" width="16" height="17" rx="2"/><path d="M8 2v4m8-4v4M8 11h8m-8 4h5"/>',
  devices:
    '<rect x="7" y="2" width="10" height="20" rx="3"/><path d="M10 6h4m-3 12h2M2 9a8 8 0 0 0 0 6m20-6a8 8 0 0 1 0 6"/>',
  settings:
    '<path d="m12 3 2 3 4 .3.5 4 2.5 2.7-2.5 2.7-.5 4-4 .3-2 3-2-3-4-.3-.5-4L3 13l2.5-2.7.5-4 4-.3Z"/><circle cx="12" cy="13" r="3"/>',
  play: '<path d="m8 5 11 7-11 7Z"/>',
  pause: '<path d="M8 5v14m8-14v14"/>',
  save: '<path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h12l4 4v12a2 2 0 0 1-2 2Z"/><path d="M7 3v6h10V3M7 21v-8h10v8"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  arrow: '<path d="M7 17 17 7M7 7h10v10"/>',
  download: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/>',
  record: '<circle cx="12" cy="12" r="6" fill="currentColor" stroke="none"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="1" fill="currentColor" stroke="none"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10v1"/>',
  trash: '<path d="M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7m4-7v7"/>',
  back: '<path d="m14 6-6 6 6 6"/>',
};
const svg = (name) =>
  `<svg viewBox="0 0 24 24" aria-hidden="true">${icons[name] || icons.wave}</svg>`;
const esc = (x) =>
  String(x ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const preview = makePreview(DATA),
  durationNs = preview.samples.at(-1).timeNs;
const state = {
  page: "Monitor",
  cursorNs: 6e9,
  zoom: 5,
  playing: false,
  recording: false,
  recordStartNs: 0,
  markers: [
    { id: "m1", timeNs: 2.1e9, label: "Reference beat" },
    { id: "m2", timeNs: 5.4e9, label: "Review interval" },
  ],
  sessions: [],
  sessionQuery: "",
  sessionSort: "newest",
  db: null,
  storageReady: false,
  storageError: "",
  review: null,
  scenario: "clean",
  scenarioResult: qualityScenario("clean"),
  reduceMotion: false,
};
let lastFrame = 0,
  toastTimer;
const toast = (message) => {
  document.querySelector("#toast").textContent = message;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(
    () => (document.querySelector("#toast").textContent = ""),
    3400,
  );
};
const fmt = (ns) => `${(ns / 1e9).toFixed(2)} s`;
const active = () =>
  state.review || {
    id: "preview-mitdb-100",
    name: "Record 100 · ECG excerpt",
    source: "PREVIEW",
    startedAtEpochMs: Date.now(),
    channels: preview.channels,
    samples: preview.samples,
    markers: state.markers,
    stats: preview.stats,
  };
const activeDuration = () => {
  const s = active().samples;
  return s.length ? s.at(-1).timeNs : 0;
};
const footer = () =>
  `<footer class="footer"><span>BioSignal Bridge · 1.1.0<br>Independent sensor software. Public data and an openly documented packet format.</span><span>ECG: <a href="https://physionet.org/content/mitdb/1.0.0/" target="_blank" rel="noopener">MIT-BIH Arrhythmia Database</a> · Record 100<br>PhysioNet · ODC Attribution License 1.0</span></footer>`;
function shell() {
  document.querySelector("#app").innerHTML =
    `<div class="shell"><aside class="sidebar"><div class="brand"><span class="brandmark">${svg("wave")}</span><span>BioSignal<br>Bridge<small>SIGNAL WORKSPACE</small></span></div><nav class="nav" aria-label="Main navigation">${["Monitor", "Sessions", "Devices", "Settings"].map((n) => `<button data-nav="${n}" class="${state.page === n ? "active" : ""}" ${state.page === n ? 'aria-current="page"' : ""} aria-label="${n}">${svg(n.toLowerCase())}<span>${n}</span></button>`).join("")}</nav><div class="side-note"><strong>Keep the context.</strong>Every sample has a time.<br>Every recording has a source.</div><div class="version">BIO / 01<span style="float:right">1.1.0</span></div></aside><main class="main"><header class="topbar"><div><div class="eyebrow">BioSignal Bridge</div><h1>${state.page === "Monitor" ? "Signal workspace" : state.page === "Sessions" ? "Your recordings" : state.page === "Devices" ? "Sensor connection" : "Workspace settings"}</h1></div><div class="topbar-right"><span class="pill source">${state.review ? "SAVED PREVIEW" : "PUBLIC DATA PREVIEW"}</span><button class="shortcut-help" id="shortcut-help" aria-label="Keyboard shortcuts" title="Keyboard shortcuts">?</button></div></header><div id="page"></div>${footer()}</main></div>`;
  document
    .querySelectorAll("[data-nav]")
    .forEach((b) => (b.onclick = () => navigate(b.dataset.nav)));
  document.querySelector("#shortcut-help").onclick = shortcutsDialog;
  renderPage();
}
function navigate(page) {
  state.page = page;
  shell();
  window.scrollTo(0, 0);
}
function renderPage() {
  const box = document.querySelector("#page");
  box.innerHTML =
    state.page === "Monitor"
      ? monitor()
      : state.page === "Sessions"
        ? sessions()
        : state.page === "Devices"
          ? devices()
          : settings();
  bindPage();
}
function monitor() {
  const session = active(),
    samples = session.samples;
  return `${state.review ? `<div class="review-banner"><div><strong>${esc(state.review.name)}</strong><span>Saved locally · ${samples.length.toLocaleString()} samples · source preserved as Preview</span></div><button class="btn" id="close-review">${svg("back")}Back to preview</button></div>` : ""}<section class="intro"><div><h2>${state.review ? "Review the details." : "The signal, with its story."}</h2><p>${state.review ? "Move through the recording and revisit its markers." : "Play a public recording, mark a moment and take the details with you."}</p></div><div class="actions"><button class="btn" id="save-excerpt">${svg("save")}Save excerpt</button><button class="btn primary" id="record" ${state.review ? "disabled" : ""}>${svg(state.recording ? "stop" : "record")}<span>${state.recording ? "Finish recording" : "Record preview"}</span></button></div></section><div class="metrics"><div class="metric"><div class="metric-label">${svg("wave")}Sampling rate</div><div class="metric-value">250<small>Hz</small></div><div class="metric-foot">One sample every 4 ms</div></div><div class="metric"><div class="metric-label">Signal channel</div><div class="metric-value">MLII<small>ECG</small></div><div class="metric-foot">Calibrated public source</div></div><div class="metric"><div class="metric-label">Samples in recording</div><div class="metric-value">${samples.length.toLocaleString()}</div><div class="metric-foot">${fmt(activeDuration())} · 16-bit values</div></div><div class="metric"><div class="metric-label">Recording status</div><div class="metric-value" style="font-size:22px" id="record-status">${state.recording ? "Recording" : "Ready"}</div><div class="metric-foot">${state.recording ? "Saving preview samples locally" : "Preview replay · no sensor connected"}</div></div></div><div class="workspace"><section class="panel wave-panel" aria-label="ECG waveform"><div class="panel-head"><div><h3>ECG · Lead MLII</h3><p>Record 100 / 00:00–00:12 · 0.005 mV per count</p></div><div class="head-meta"><i class="dot"></i><span id="play-status">${state.playing ? "PLAYING" : "PAUSED"}</span></div></div><div class="wave-body"><div class="plot-labels"><span>Amplitude / mV</span><span id="window-label"></span></div><div class="wave-chart" id="wave-chart"><span class="axis-y" style="top:0">+1.5</span><span class="axis-y" style="top:33.33%">+0.5</span><span class="axis-y" style="top:66.66%">−0.5</span><span class="axis-y" style="top:100%">−1.5</span><svg viewBox="0 0 1000 260" preserveAspectRatio="none" role="img" aria-label="ECG waveform in millivolts"><path id="wave-path" class="wave-path"/></svg><div id="plot-markers"></div></div><div class="time-axis" id="time-axis"></div></div><div class="transport"><div class="transport-row"><button class="play" id="play" aria-label="${state.playing ? "Pause playback" : "Play recording"}">${svg(state.playing ? "pause" : "play")}</button><span class="transport-time" id="transport-time"></span><div class="zoom" role="group" aria-label="Time window">${[2, 5, 10].map((z) => `<button data-zoom="${z}" class="${state.zoom === z ? "selected" : ""}" aria-pressed="${state.zoom === z}">${z} s</button>`).join("")}</div></div><label class="seek-label"><span><span>Recording position</span><span>${fmt(activeDuration())}</span></span><input class="seek" id="seek" ${state.recording ? "disabled" : ""} type="range" aria-label="Recording position" min="0" max="${Math.round(activeDuration() / 1e6)}" step="4" value="${Math.round(state.cursorNs / 1e6)}"></label><div class="overview-track" role="img" aria-label="Full recording overview"><svg viewBox="0 0 1000 42" preserveAspectRatio="none" aria-hidden="true"><path id="overview-path"/></svg><span id="overview-window"></span></div><div class="window-inspection" id="window-inspection" role="group" aria-label="Visible window details"></div></div></section><aside class="aside-stack"><section class="source-card"><div class="eyebrow">Know your source</div><h3>A real source.<br>Room to look closer.</h3><p>MIT-BIH record 100, lead MLII. Twelve seconds of public ECG data, replayed locally so you can try the recording tools. This preview is for signal review.</p><a href="https://physionet.org/content/mitdb/1.0.0/" target="_blank" rel="noopener">View source & attribution ${svg("arrow")}</a><div class="source-details"><div><span>Original</span><strong>360 Hz</strong></div><div><span>Preview</span><strong>250 Hz</strong></div><div><span>Lead</span><strong>MLII</strong></div></div></section><section class="panel"><div class="panel-head"><div><h3>Event markers</h3><p>A note at the moment it matters</p></div><span class="pill" id="marker-count">${session.markers.length}</span></div><div class="markers" id="markers-list">${markerList()}</div></section></aside></div><div class="section-head"><h2>Packet quality, made visible</h2><small>Separate transport simulation</small></div><section class="panel quality"><div class="quality-copy"><h3>See how the stream handles a problem.</h3><p>These 12 small test packets are independent of the ECG above. Every counter comes from the packet decoder and sequence checks.</p><div class="scenario-row"><label class="sr-only" for="scenario">Packet scenario</label><select id="scenario">${[
    ["clean", "In order"],
    ["loss", "Two packets lost"],
    ["duplicate", "Duplicate packet"],
    ["reorder", "Packets out of order"],
    ["reset", "Device clock restart"],
    ["malformed", "Malformed frame"],
  ]
    .map(
      ([v, l]) =>
        `<option value="${v}" ${state.scenario === v ? "selected" : ""}>${l}</option>`,
    )
    .join(
      "",
    )}</select><button id="run-scenario" class="btn soft">Run check</button></div></div><div id="quality-stats">${qualityStats()}</div><div class="quality-note">${svg("info")}<span>The excerpt is not changed by this simulation. No packet-loss claim is made about the original ECG. Session exports retain the counters for their own samples.</span></div></section>`;
}
function markerList() {
  return active().markers.length
    ? active()
        .markers.map(
          (m, i) =>
            `<button class="marker" data-marker="${esc(m.id)}" aria-label="Edit marker ${esc(m.label)}"><span class="marker-num">${String(i + 1).padStart(2, "0")}</span><span class="marker-label">${esc(m.label)}<small>${fmt(m.timeNs)}</small></span><span class="edit">↗</span></button>`,
        )
        .join("") +
        `<button class="add-marker" id="add-marker">${svg("plus")}Add marker at playhead</button>`
    : `<p class="help" style="padding-top:17px">Add a short note at the current playhead.</p><button class="add-marker" id="add-marker">${svg("plus")}Add marker at playhead</button>`;
}
function qualityStats() {
  const s = state.scenarioResult.stats;
  return `<div class="quality-stats">${[
    ["Received", s.receivedPackets],
    ["Accepted", s.acceptedPackets],
    ["Lost", s.lostPackets],
    ["Duplicates", s.duplicatePackets],
    ["Out of order", s.outOfOrderPackets],
    ["Clock resets", s.clockResets],
  ]
    .map(
      ([l, v]) =>
        `<div class="quality-stat"><strong>${v}</strong><span>${l}</span></div>`,
    )
    .join(
      "",
    )}</div><div class="scenario-history" role="group" aria-label="Packet processing results">${state.scenarioResult.events.map((e) => `<span class="packet ${e.status !== "ACCEPTED" ? "bad" : ""}" title="${e.status}">${e.sequence}${e.status === "ACCEPTED" ? " ✓" : e.status === "CLOCK_RESET_PENDING" ? " …" : " ×"}</span>`).join("")}</div><p class="help" style="font-size:10px;margin-top:7px">Malformed frames: ${s.malformedPackets} · Every counter describes this check only.</p>`;
}
function sessions() {
  return `<section class="intro"><div><div class="eyebrow">Your signal library</div><h2>A record worth keeping.</h2><p>Find a recording, revisit its notes and export the samples when you need them.</p></div><button class="btn primary" id="go-monitor">${svg("plus")}New recording</button></section>
    <div class="library-toolbar"><label class="library-search"><span class="sr-only">Search recordings</span>${svg("sessions")}<input id="session-search" type="search" aria-label="Search recordings" placeholder="Search a name or event note" value="${esc(state.sessionQuery)}"></label><label class="library-sort"><span class="sr-only">Sort recordings</span><select id="session-sort" aria-label="Sort recordings">${[["newest","Newest first"],["name","By name"],["longest","Longest first"]].map(([value,label]) => `<option value="${value}" ${state.sessionSort === value ? "selected" : ""}>${label}</option>`).join("")}</select></label><p class="help" id="storage-status">${state.storageError ? esc(state.storageError) : state.storageReady ? `${state.sessions.length} stored locally` : "Opening your library…"}</p></div>
    <div class="session-grid" id="session-results">${sessionCards(findSessions(state.sessions,state.sessionQuery,state.sessionSort))}</div>`;
}
function sessionCards(sessions) {
  if (!sessions.length) return `<div class="panel empty">${svg("sessions")}<h3>${state.sessionQuery ? "No matching recordings." : "Your first recording starts here."}</h3><p class="help">${state.sessionQuery ? "Try another name or a word from an event note." : "Save the public excerpt, or record a shorter passage from the monitor."}</p><button class="btn primary" id="empty-record">Go to monitor ${svg("arrow")}</button></div>`;
  return sessions.map(s => `<article class="panel session"><div class="session-top"><span class="pill source">${esc(s.source)}</span><span class="help">${new Date(s.startedAtEpochMs).toLocaleDateString("en-GB", {day:"2-digit",month:"short",year:"numeric"})}</span></div><h3>${esc(s.name)}</h3><p class="help">MIT-BIH record 100 · ECG / MLII</p><div class="session-spark" aria-hidden="true"><svg viewBox="0 0 1000 70" preserveAspectRatio="none"><path d="${pathFor(s.samples,0,s.samples.at(-1)?.timeNs || 1,s.channels[0].scalePerCount,1000,70)}"/></svg></div><div class="session-meta"><span><strong>${s.samples.length.toLocaleString()}</strong>samples</span><span><strong>${fmt(s.samples.at(-1)?.timeNs || 0)}</strong>duration</span><span><strong>${s.markers.length}</strong>notes</span></div><div class="actions"><button class="btn primary" data-review="${esc(s.id)}">${svg("play")}Review</button><button class="btn" data-export="${esc(s.id)}" data-format="csv">${svg("download")}CSV</button><button class="btn" data-export="${esc(s.id)}" data-format="json">JSON</button><button class="btn" data-rename="${esc(s.id)}">Rename</button><button class="btn icononly danger" data-delete="${esc(s.id)}" aria-label="Delete ${esc(s.name)}">${svg("trash")}</button></div></article>`).join("");
}
function bindSessionActions() {
  document.querySelector("#empty-record")?.addEventListener("click", () => navigate("Monitor"));
  document.querySelectorAll("[data-review]").forEach(button => button.onclick = async () => {
    if (state.recording) await finishRecording();
    state.review = state.sessions.find(session => session.id === button.dataset.review);
    state.cursorNs = Math.min(5e9, activeDuration());
    state.playing = false;
    navigate("Monitor");
  });
  document.querySelectorAll("[data-export]").forEach(button => button.onclick = () => download(state.sessions.find(session => session.id === button.dataset.export),button.dataset.format));
  document.querySelectorAll("[data-rename]").forEach(button => button.onclick = () => renameDialog(button.dataset.rename));
  document.querySelectorAll("[data-delete]").forEach(button => button.onclick = () => deleteDialog(button.dataset.delete));
}
function refreshSessionResults() {
  document.querySelector("#session-results").innerHTML = sessionCards(findSessions(state.sessions,state.sessionQuery,state.sessionSort));
  bindSessionActions();
}
function devices() {
  return `<section class="intro"><div><h2>Make the connection explicit.</h2><p>The Android app connects to compatible BLE peripherals. This browser workspace plays public data.</p></div><span class="pill">PROTOCOL v1</span></section><div class="device-layout"><section class="panel device-hero"><div class="eyebrow" style="color:#bac6e6">Sensor interface</div><div class="instrument"><div class="instrument-shape" aria-hidden="true"><div class="instrument-display">${svg("wave")}</div></div><div><h3>One clear stream.</h3><p>A compact frame carries channel, device time, sequence and up to four samples.</p><span class="pill" style="background:#394266;color:#e2e7f9">NO DEVICE CONNECTED</span></div></div><p>Device discovery and capture are available in the Android application. The demo has no Bluetooth controls.</p></section><section class="panel technical"><dl><dt>Transport</dt><dd>Bluetooth Low Energy / GATT notification</dd><dt>Service UUID</dt><dd class="code">9f7c0001-57ae-4fab-8a12-9c2ed7a064b1</dd><dt>Stream characteristic</dt><dd class="code">9f7c0002-57ae-4fab-8a12-9c2ed7a064b1</dd><dt>Sample values</dt><dd>Signed 16-bit counts · per-channel calibration</dd><dt>Timing</dt><dd>Device microseconds → aligned nanoseconds</dd></dl></section></div><section class="panel protocol"><h3 style="margin:0 0 12px;font-size:15px">A frame you can inspect</h3><p class="help" style="margin-bottom:16px">20 bytes · little endian · version 1 · unused sample slots must be zero.</p><table><thead><tr><th>Offset</th><th>Field</th><th>Meaning</th></tr></thead><tbody>${[
    ["0–1", "0xB1 / 0x01", "Magic byte and version"],
    ["2–3", "Sequence", "Unsigned 16-bit packet counter"],
    ["4–7", "Device tick", "Unsigned 32-bit microseconds"],
    ["8–9", "Period", "Microseconds between samples"],
    ["10–11", "Channel / count", "Channel ID and 1–4 samples"],
    ["12–19", "Samples", "Signed 16-bit values; zero padding"],
  ]
    .map(
      (r) =>
        `<tr>${r.map((x, i) => `<td${i === 1 ? ' class="code"' : ""}>${x}</td>`).join("")}</tr>`,
    )
    .join(
      "",
    )}</tbody></table></section><section class="panel setting-static" style="margin-top:20px"><h3>Calibration belongs with the data.</h3><p>The public ECG preview uses 0.005 mV per count. A live peripheral begins with raw ADC counts unless its calibration is known. Channel metadata travels with exported recordings so analysis code can apply the right units.</p></section>`;
}
function settings() {
  return `<section class="intro"><div><h2>A small workspace. Clear boundaries.</h2><p>Keep recordings local and their source visible.</p></div></section><div class="settings-layout"><section class="panel setting"><div><h3>Reduce motion</h3><p>Keep interface changes immediate. Waveform playback remains under your control.</p></div><button id="motion" class="toggle" role="switch" aria-label="Reduce motion" aria-checked="${state.reduceMotion}"></button></section><section class="panel setting"><div><h3>Local recording database</h3><p>${state.storageError ? esc(state.storageError) : `Recordings, event notes and packet counters stay in this browser. ${state.sessions.length} session${state.sessions.length === 1 ? "" : "s"} saved.`}<br>Clearing browser data removes these recordings. Export a copy to keep them.</p></div>${svg("sessions")}</section><section class="panel setting-static"><h3>Data and privacy</h3><p>The demo uses a bundled public ECG excerpt. It makes no background requests and uploads no recordings. Export files are created on this device. For recordings from people, use an appropriate consent and data-handling process.</p></section><section class="panel setting-static"><h3>Confidentiality and NDA</h3><p>This implementation respects confidentiality and NDA boundaries. It was built independently from public concepts and an original sample protocol. No employer code, customer recordings, private hardware designs or confidential product details are included.</p></section><section class="panel setting-static"><h3>Source and scope</h3><p>MIT-BIH Arrhythmia Database, record 100, MLII, first 12 seconds. The original 360 Hz signal is resampled to 250 Hz and rounded to 200 counts per millivolt for this preview. This workspace supports signal inspection and software development; it does not interpret a recording as a diagnosis.</p></section><section class="panel setting-static"><h3>BioSignal Bridge 1.1.0</h3><p>Android signal capture and review, with a companion offline workspace.<br>Public ECG data: Moody GB, Mark RG. The impact of the MIT-BIH Arrhythmia Database. IEEE Engineering in Medicine and Biology Magazine 20(3):45–50, 2001. PhysioNet / ODC Attribution License 1.0.</p></section></div>`;
}
function bindPage() {
  if (state.page === "Monitor") {
    document.querySelector("#play").onclick = togglePlay;
    document.querySelector("#seek").oninput = (e) => {
      state.playing = false;
      state.cursorNs = Number(e.target.value) * 1e6;
      updateWave();
      updatePlay();
    };
    document.querySelectorAll("[data-zoom]").forEach(
      (b) =>
        (b.onclick = () => {
          state.zoom = Number(b.dataset.zoom);
          document.querySelectorAll("[data-zoom]").forEach((x) => {
            x.classList.toggle("selected", x === b);
            x.setAttribute("aria-pressed", String(x === b));
          });
          updateWave();
        }),
    );
    document.querySelector("#record").onclick = record;
    document.querySelector("#save-excerpt").onclick = () =>
      save(active().samples, active().markers, "ECG excerpt · Record 100");
    document.querySelector("#add-marker").onclick = () => markerDialog();
    document
      .querySelectorAll("[data-marker]")
      .forEach((b) => (b.onclick = () => markerDialog(b.dataset.marker)));
    document.querySelector("#run-scenario").onclick = () => {
      state.scenario = document.querySelector("#scenario").value;
      state.scenarioResult = qualityScenario(state.scenario);
      document.querySelector("#quality-stats").innerHTML = qualityStats();
      toast("Packet check complete. The ECG excerpt is unchanged.");
    };
    document.querySelector("#close-review")?.addEventListener("click", () => {
      state.review = null;
      state.cursorNs = 6e9;
      state.playing = false;
      renderPage();
    });
    updateWave();
  }
  if (state.page === "Sessions") {
    document.querySelector("#go-monitor").onclick = () => navigate("Monitor");
    document.querySelector("#session-search").oninput = event => {
      state.sessionQuery = event.target.value; refreshSessionResults();
    };
    document.querySelector("#session-sort").onchange = event => {
      state.sessionSort = event.target.value; refreshSessionResults();
    };
    bindSessionActions();
  }
  document.querySelector("#motion")?.addEventListener("click", (e) => {
    state.reduceMotion = !state.reduceMotion;
    document.documentElement.dataset.motion = state.reduceMotion ? "reduced" : "full";
    e.currentTarget.setAttribute("aria-checked", state.reduceMotion);
    document.documentElement.style.scrollBehavior = state.reduceMotion
      ? "auto"
      : "";
    try {
      localStorage.setItem("biosignal-motion", String(state.reduceMotion));
    } catch {}
  });
}
function updatePlay() {
  const b = document.querySelector("#play");
  if (b) {
    b.setAttribute(
      "aria-label",
      state.playing ? "Pause playback" : "Play recording",
    );
    b.innerHTML = svg(state.playing ? "pause" : "play");
    document.querySelector("#play-status").textContent = state.playing
      ? "PLAYING"
      : "PAUSED";
  }
}
function togglePlay() {
  if (state.cursorNs >= activeDuration()) state.cursorNs = 0;
  state.playing = !state.playing;
  lastFrame = performance.now();
  updatePlay();
}
function updateWave() {
  if (state.page !== "Monitor") return;
  const s = active(),
    endMax = activeDuration(),
    span = Math.min(state.zoom * 1e9, endMax || 1),
    start = Math.min(
      Math.max(0, state.cursorNs - span),
      Math.max(0, endMax - span),
    ),
    end = start + span;
  document
    .querySelector("#wave-path")
    .setAttribute(
      "d",
      pathFor(s.samples, start, end, s.channels[0].scalePerCount),
    );
  document.querySelector("#window-label").textContent =
    `${fmt(start)} – ${fmt(end)}`;
  document.querySelector("#transport-time").textContent =
    `${fmt(state.cursorNs)} / ${fmt(endMax)}`;
  const inspection = inspectWindow(s.samples,s.channels[0],start,end);
  const value = number => number === null ? "—" : number.toFixed(3);
  document.querySelector("#window-inspection").innerHTML = [
    ["Minimum",value(inspection.minimum),s.channels[0].unit],
    ["Maximum",value(inspection.maximum),s.channels[0].unit],
    ["RMS",value(inspection.rms),s.channels[0].unit],
    ["Observed rate",inspection.observedRateHz?.toFixed(1) || "—","Hz"],
  ].map(([label,value,unit]) => `<div><span>${label}</span><strong>${value}<small>${esc(unit)}</small></strong></div>`).join("");
  const overviewPath = document.querySelector("#overview-path");
  if (overviewPath.dataset.session !== s.id) {
    overviewPath.setAttribute("d",pathFor(s.samples,0,endMax || 1,s.channels[0].scalePerCount,1000,42));
    overviewPath.dataset.session = s.id;
  }
  const overview = document.querySelector("#overview-window");
  overview.style.left = `${endMax ? start/endMax*100 : 0}%`;
  overview.style.width = `${endMax ? span/endMax*100 : 100}%`;
  const seek = document.querySelector("#seek");
  if (document.activeElement !== seek)
    seek.value = Math.round(state.cursorNs / 1e6);
  document.querySelector("#time-axis").innerHTML = Array.from(
    { length: 6 },
    (_, i) => `<span>${((start + (span * i) / 5) / 1e9).toFixed(1)} s</span>`,
  ).join("");
  document.querySelector("#plot-markers").innerHTML = s.markers
    .filter((m) => m.timeNs >= start && m.timeNs <= end)
    .map((m) => {
      const pct = ((m.timeNs - start) / span) * 100;
      return `<div class="marker-line" style="left:${pct}%"><span style="${pct > 75 ? "right:5px" : "left:5px"}">${esc(m.label)}</span></div>`;
    })
    .join("");
}
function frame(now) {
  if (state.playing) {
    const step = Math.min(now - (lastFrame || now), 100);
    state.cursorNs = Math.min(activeDuration(), state.cursorNs + step * 1e6);
    if (state.cursorNs >= activeDuration()) {
      state.playing = false;
      if (state.recording) finishRecording();
      updatePlay();
    }
    updateWave();
  }
  lastFrame = now;
  requestAnimationFrame(frame);
}
function record() {
  if (state.recording) return finishRecording();
  if (!state.db)
    return toast(
      state.storageError || "The recording database is still opening.",
    );
  state.cursorNs = 0;
  state.recordStartNs = 0;
  state.recording = true;
  state.playing = true;
  lastFrame = performance.now();
  renderPage();
  toast("Recording the public preview. No sensor is connected.");
}
async function finishRecording() {
  state.recording = false;
  state.playing = false;
  const samples = preview.samples.filter(
    (s) => s.timeNs >= state.recordStartNs && s.timeNs <= state.cursorNs,
  );
  if (!samples.length) {
    renderPage();
    return toast("Play a short passage before finishing the recording.");
  }
  await save(
    samples,
    state.markers.filter(
      (m) => m.timeNs >= state.recordStartNs && m.timeNs <= state.cursorNs,
    ),
    "Preview recording · MLII",
  );
  renderPage();
}
async function save(samples, markers, name) {
  if (!state.db)
    return toast(
      state.storageError || "The recording database is still opening.",
    );
  const origin = samples[0]?.timeNs || 0,
    id =
      "session-" +
      Date.now().toString(36) +
      "-" +
      Math.random().toString(36).slice(2, 7);
  const normalized = samples.map((s) => ({ ...s, timeNs: s.timeNs - origin }));
  const packetCount = new Set(normalized.map((s) => s.sequence)).size;
  const session = {
    id,
    name,
    source: "PREVIEW",
    startedAtEpochMs: Date.now(),
    channels: preview.channels,
    samples: normalized,
    markers: markers
      .filter((m) => m.timeNs >= origin && m.timeNs <= samples.at(-1).timeNs)
      .map((m) => ({ ...m, timeNs: m.timeNs - origin })),
    stats: {
      ...preview.stats,
      receivedPackets: packetCount,
      acceptedPackets: packetCount,
    },
  };
  try {
    await state.db.save(session);
    state.sessions = await listSessions();
    toast(
      `Saved ${normalized.length.toLocaleString()} samples in the local database.`,
    );
    if (state.page === "Sessions") renderPage();
  } catch (e) {
    toast(
      "The browser could not save this session. Check its storage permissions.",
    );
  }
}
function markerDialog(id) {
  state.playing = false;
  updatePlay();
  const existing = active().markers.find((m) => m.id === id);
  if (existing && !state.recording) {
    state.cursorNs = existing.timeNs;
    updateWave();
  }
  const dialog = document.querySelector("#dialog");
  dialog.innerHTML = `<form id="marker-form"><h2>${existing ? "Edit marker" : "Add an event marker"}</h2><p>A short label at ${fmt(existing?.timeNs ?? state.cursorNs)} helps you find this moment again.</p><label for="marker-label">Marker label</label><input id="marker-label" maxlength="120" value="${esc(existing?.label || "")}" placeholder="For example, sensor repositioned" autocomplete="off" required><div class="error" id="marker-error" role="alert"></div><div class="actions"><button class="btn" type="button" id="cancel-dialog">Cancel</button><button class="btn primary" type="submit">${existing ? "Save marker" : "Add marker"}</button></div></form>`;
  dialog.setAttribute("aria-label", "Event marker");
  dialog.showModal();
  dialog.querySelector("#cancel-dialog").onclick = () => dialog.close();
  dialog.querySelector("form").onsubmit = async (e) => {
    e.preventDefault();
    try {
      const label = validateMarker(dialog.querySelector("input").value);
      const markers = active().markers;
      if (existing) existing.label = label;
      else
        markers.push({
          id: "marker-" + Date.now(),
          timeNs: Math.round(state.cursorNs / 1e6) * 1e6,
          label,
        });
      markers.sort((a, b) => a.timeNs - b.timeNs);
      if (state.review) {
        await state.db.save(state.review);
        state.sessions = await listSessions();
      }
      dialog.close();
      renderPage();
      toast(existing ? "Marker updated." : "Marker added at the playhead.");
    } catch (error) {
      dialog.querySelector("#marker-error").textContent = error.message;
    }
  };
}
function deleteDialog(id) {
  const s = state.sessions.find((x) => x.id === id),
    dialog = document.querySelector("#dialog");
  dialog.innerHTML = `<h2>Delete this recording?</h2><p>${esc(s.name)} will be removed from this browser. Keep an export first if you need a copy.</p><div class="actions"><button class="btn" id="cancel-dialog">Keep recording</button><button class="btn danger" id="confirm-delete">Delete recording</button></div>`;
  dialog.setAttribute("aria-label", "Delete recording");
  dialog.showModal();
  dialog.querySelector("#cancel-dialog").onclick = () => dialog.close();
  dialog.querySelector("#confirm-delete").onclick = async () => {
    try {
      await state.db.remove(id);
      state.sessions = await listSessions();
      if (state.review?.id === id) state.review = null;
      dialog.close(); renderPage(); toast("Recording deleted from this browser.");
    } catch { toast("This recording could not be deleted. Reopen the workspace and try again."); }
  };
}
function download(session, format) {
  const text =
      format === "csv"
        ? exportCsv(session)
        : JSON.stringify(exportMetadata(session), null, 2) + "\n",
    blob = new Blob([text], {
      type: format === "csv" ? "text/csv;charset=utf-8" : "application/json",
    }),
    url = URL.createObjectURL(blob),
    a = document.createElement("a");
  a.href = url;
  a.download = `${session.id}.${format}`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  toast(
    format === "csv"
      ? "Sample CSV exported. Values remain in raw counts."
      : "Session metadata exported with units, markers and packet counters.",
  );
}
async function listSessions() { return state.db.list(); }
async function openDb() {
  try {
    state.db = await LocalSessionRepository.open(() => {
      state.db = null; state.storageReady = false;
      state.storageError = "The library changed in another tab. Reopen this page to save recordings.";
      if (["Sessions","Settings"].includes(state.page)) renderPage();
    });
    state.sessions = await listSessions(); state.storageReady = true;
  } catch (error) {
    state.db = null; state.storageReady = false;
    state.storageError = error.message || "Local storage is unavailable here. Open the demo in a regular browser window to save recordings.";
  }
  if (["Sessions","Settings"].includes(state.page)) renderPage();
}
function renameDialog(id) {
  const session = state.sessions.find(item => item.id === id), dialog = document.querySelector("#dialog");
  dialog.innerHTML = `<form><h2>Rename this recording</h2><p>Choose a name you will recognise when you return.</p><label for="session-name">Session name</label><input id="session-name" value="${esc(session.name)}" maxlength="120" required><div class="error" id="rename-error" role="alert"></div><div class="actions"><button class="btn" type="button" id="cancel-dialog">Cancel</button><button class="btn primary" type="submit">Save name</button></div></form>`;
  dialog.setAttribute("aria-label", "Rename recording");
  dialog.showModal();
  dialog.querySelector("#cancel-dialog").onclick = () => dialog.close();
  dialog.querySelector("form").onsubmit = async event => {
    event.preventDefault();
    try {
      const renamed = {...session,name:validateSessionName(dialog.querySelector("input").value)};
      await state.db.save(renamed); state.sessions = await listSessions();
      if (state.review?.id === id) state.review = renamed;
      dialog.close(); renderPage(); toast("Recording renamed.");
    } catch (error) { dialog.querySelector("#rename-error").textContent = error.message; }
  };
}
function shortcutsDialog() {
  const dialog = document.querySelector("#dialog");
  dialog.innerHTML = `<h2>A few useful shortcuts</h2><p>While the monitor is open:</p><dl class="shortcut-list"><dt><kbd>Space</kbd></dt><dd>Play or pause</dd><dt><kbd>←</kbd> <kbd>→</kbd></dt><dd>Move one second</dd><dt><kbd>M</kbd></dt><dd>Add a note at the playhead</dd></dl><button class="btn primary" id="close-help">Back to workspace</button>`;
  dialog.setAttribute("aria-label", "Keyboard shortcuts");
  dialog.showModal(); dialog.querySelector("#close-help").onclick = () => dialog.close();
}
document.addEventListener("keydown",event => {
  if (event.repeat || state.page !== "Monitor" || document.querySelector("#dialog").open ||
      event.ctrlKey || event.metaKey || event.altKey ||
      event.target.closest("input,select,textarea,button,a,[contenteditable]")) return;
  if (event.code === "Space") { event.preventDefault(); togglePlay(); }
  else if (["ArrowLeft","ArrowRight"].includes(event.key) && !state.recording) {
    event.preventDefault(); state.playing = false;
    state.cursorNs = Math.max(0,Math.min(activeDuration(),state.cursorNs+(event.key === "ArrowLeft" ? -1e9 : 1e9)));
    updateWave(); updatePlay();
  } else if (event.key.toLowerCase() === "m") { event.preventDefault(); markerDialog(); }
});
try {
  state.reduceMotion = localStorage.getItem("biosignal-motion") === "true";
} catch {}
document.documentElement.dataset.motion = state.reduceMotion ? "reduced" : "full";
shell();
openDb();
requestAnimationFrame(frame);
