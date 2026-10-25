import fs from "node:fs/promises";
import http from "node:http";
import path from "node:path";
import assert from "node:assert/strict";
import { chromium } from "playwright";
import AxeBuilder from "@axe-core/playwright";
const root = path.resolve(import.meta.dirname, "..");
const html = await fs.readFile(path.join(root, "index.html"));
const server = http.createServer((req, res) => {
  res.writeHead(200, { "Content-Type": "text/html" });
  res.end(html);
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const url = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({
  headless: true,
  args: ["--no-sandbox"],
});
const context = await browser.newContext({
  viewport: { width: 1440, height: 1100 },
  acceptDownloads: true,
});
const page = await context.newPage();
const errors = [],
  requests = [],
  checks = [],
  audits = [];
page.on("pageerror", (e) => errors.push(e.message));
page.on("request", (r) => requests.push(r.url()));
const pass = (name) => {
  checks.push(name);
  console.log(`✓ ${name}`);
};
const nav = async (name) =>
  page
    .getByRole("navigation", { name: "Main navigation" })
    .getByRole("button", { name, exact: true })
    .click();
async function fits(name) {
  const r = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    width: innerWidth,
    bad: [...document.querySelectorAll("body *")]
      .filter((e) => {
        const r = e.getBoundingClientRect();
        return r.width && r.right > innerWidth + 1;
      })
      .map((e) => ({
        tag: e.tagName,
        class: e.className,
        right: e.getBoundingClientRect().right,
      }))
      .slice(0, 10),
  }));
  assert.ok(r.scroll <= r.width + 1, `${name}: ${JSON.stringify(r)}`);
}
async function audit(name, screenshot) {
  const r = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  audits.push({
    name,
    violations: r.violations.map((v) => ({
      id: v.id,
      impact: v.impact,
      nodes: v.nodes.map((n) => n.target),
    })),
  });
  assert.deepEqual(
    r.violations.map((v) => v.id),
    [],
    `${name} accessibility: ${JSON.stringify(r.violations.map((v) => ({ id: v.id, targets: v.nodes.map((n) => n.target) })))}`,
  );
  await fits(name);
  if (screenshot) {
    await page.evaluate(
      () => (document.querySelector("#toast").textContent = ""),
    );
    await page.screenshot({
      path: path.join(root, "docs/screenshots", screenshot),
      fullPage: !name.startsWith("390px"),
    });
  }
  pass(name + " accessibility and layout");
}
try {
  await page.goto(url);
  await page.waitForFunction(
    () =>
      document.querySelector("#wave-path")?.getAttribute("d")?.length > 1000,
  );
  await page.waitForFunction(async () => {
    const r = await new Promise((resolve, reject) => {
      const q = indexedDB.open("biosignal-bridge", 1);
      q.onsuccess = () => {
        q.result.close();
        resolve(true);
      };
      q.onerror = reject;
    });
    return r;
  });
  assert.equal(
    await page.getByText("PUBLIC DATA PREVIEW", { exact: true }).count(),
    1,
  );
  assert.ok(await page.locator("#wave-path").getAttribute("d"));
  pass("real public ECG loads without network data");
  await audit("desktop Monitor", "companion-monitor.png");
  const old = await page.locator("#wave-path").getAttribute("d");
  await page.getByRole("button", { name: "2 s", exact: true }).click();
  assert.notEqual(await page.locator("#wave-path").getAttribute("d"), old);
  assert.equal(
    await page
      .getByRole("button", { name: "2 s", exact: true })
      .getAttribute("aria-pressed"),
    "true",
  );
  pass("time zoom redraws calibrated waveform");
  await page.getByRole("slider", { name: "Recording position" }).fill("8000");
  assert.match(await page.locator("#transport-time").textContent(), /8.00 s/);
  pass("seek moves playhead to precise time");
  await page
    .getByRole("button", { name: "Play recording", exact: true })
    .click();
  await page.waitForTimeout(180);
  await page
    .getByRole("button", { name: "Pause playback", exact: true })
    .click();
  const paused = await page.locator("#transport-time").textContent();
  await page.waitForTimeout(150);
  assert.equal(await page.locator("#transport-time").textContent(), paused);
  pass("playback advances and pause retains playhead");
  await page.getByRole("button", { name: "Add marker at playhead" }).click();
  await page
    .getByRole("textbox", { name: "Marker label" })
    .fill("Sensor repositioned");
  await page.getByRole("button", { name: "Add marker", exact: true }).click();
  assert.equal(
    await page
      .getByRole("button", { name: "Edit marker Sensor repositioned" })
      .count(),
    1,
  );
  pass("event marker stores current time");
  await page
    .getByRole("button", { name: "Edit marker Sensor repositioned" })
    .click();
  await page
    .getByRole("textbox", { name: "Marker label" })
    .fill("Position checked");
  await page.getByRole("button", { name: "Save marker", exact: true }).click();
  assert.equal(
    await page
      .getByRole("button", { name: "Edit marker Position checked" })
      .count(),
    1,
  );
  pass("event marker can be renamed");
  await page
    .getByLabel("Packet scenario", { exact: true })
    .selectOption("loss");
  await page.getByRole("button", { name: "Run check" }).click();
  assert.equal(
    await page
      .locator(".quality-stat")
      .filter({ hasText: "Lost" })
      .locator("strong")
      .textContent(),
    "2",
  );
  assert.equal(
    await page
      .locator(".quality-stat")
      .filter({ hasText: "Received" })
      .locator("strong")
      .textContent(),
    "10",
  );
  pass("loss scenario computes exact packet counters");
  await page
    .getByLabel("Packet scenario", { exact: true })
    .selectOption("reset");
  await page.getByRole("button", { name: "Run check" }).click();
  assert.equal(
    await page
      .locator(".quality-stat")
      .filter({ hasText: "Clock resets" })
      .locator("strong")
      .textContent(),
    "1",
  );
  assert.equal(
    await page
      .locator(".quality-stat")
      .filter({ hasText: "Accepted" })
      .locator("strong")
      .textContent(),
    "12",
  );
  pass("clock reset requires coherent packets");
  await page.getByRole("button", { name: "Save excerpt", exact: true }).click();
  await nav("Sessions");
  await page.waitForSelector(".session");
  assert.match(await page.locator(".session").textContent(), /3,000/);
  const count = await page.evaluate(
    () =>
      new Promise((resolve, reject) => {
        const r = indexedDB.open("biosignal-bridge", 1);
        r.onsuccess = () => {
          const q = r.result
            .transaction("sessions")
            .objectStore("sessions")
            .getAll();
          q.onsuccess = () => {
            resolve(q.result.length);
            r.result.close();
          };
          q.onerror = reject;
        };
        r.onerror = reject;
      }),
  );
  assert.equal(count, 1);
  pass("session samples and markers stored in actual IndexedDB");
  const csvWait = page.waitForEvent("download");
  await page.getByRole("button", { name: "CSV", exact: true }).click();
  const csvDownload = await csvWait;
  const csv = await fs.readFile(await csvDownload.path(), "utf8");
  const rows = csv.trim().split("\n");
  assert.equal(rows.length, 3001);
  assert.equal(
    rows[0],
    "session_id,channel,sequence,timestamp_ns,device_tick_us,raw_value,gap_before",
  );
  assert.equal(rows[1].split(",")[3], "0");
  pass("CSV export has exact Python-friendly columns and relative nanoseconds");
  const jsonWait = page.waitForEvent("download");
  await page.getByRole("button", { name: "JSON", exact: true }).click();
  const jsonDownload = await jsonWait;
  const metadata = JSON.parse(
    await fs.readFile(await jsonDownload.path(), "utf8"),
  );
  assert.equal(metadata.schema, "biosignal-bridge.session/v1");
  assert.equal(metadata.source, "PREVIEW");
  assert.equal(metadata.stats.lostPackets, 0);
  assert.equal(metadata.stats.acceptedPackets, 750);
  assert.equal(metadata.channels[0].scalePerCount, 0.005);
  assert.equal(metadata.markers.at(-1).label, "Position checked");
  pass("metadata preserves provenance calibration and real session counters");
  await page.reload();
  await nav("Sessions");
  await page.waitForSelector(".session");
  assert.equal(await page.locator(".session").count(), 1);
  pass("saved session survives page reload");
  await audit("desktop Sessions", "companion-sessions.png");
  await page.getByRole("button", { name: "Review", exact: true }).click();
  assert.equal(
    await page.getByRole("button", { name: "Back to preview" }).count(),
    1,
  );
  assert.equal(
    await page
      .getByRole("button", { name: "Edit marker Position checked" })
      .count(),
    1,
  );
  pass("session review restores waveform and markers");
  await page.getByRole("button", { name: "Back to preview" }).click();
  await page
    .getByRole("button", { name: "Record preview", exact: true })
    .click();
  assert.equal(
    await page.getByRole("slider", { name: "Recording position" }).isDisabled(),
    true,
  );
  await page.waitForTimeout(160);
  await page
    .getByRole("button", { name: "Edit marker Reference beat" })
    .click();
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  assert.ok(Number(await page.locator("#seek").inputValue()) < 1000);
  pass(
    "active capture prevents seeking and marker editing cannot move its timeline",
  );
  await page
    .getByRole("button", { name: "Finish recording", exact: true })
    .click();
  await nav("Sessions");
  await page.waitForFunction(
    () => document.querySelectorAll(".session").length === 2,
  );
  const n = await page
    .locator(".session")
    .first()
    .locator(".session-meta")
    .textContent();
  assert.ok(!n.includes("3,000"));
  pass("short recording saves only the played preview samples");
  await nav("Monitor");
  await page
    .getByRole("button", { name: "Record preview", exact: true })
    .click();
  await page.waitForTimeout(160);
  await nav("Sessions");
  await page.locator("[data-review]").last().click();
  await page.getByRole("button", { name: "Back to preview" }).waitFor();
  assert.equal(
    await page
      .getByRole("button", { name: "Finish recording", exact: true })
      .count(),
    0,
  );
  await page.getByRole("button", { name: "Back to preview" }).click();
  assert.equal(
    await page
      .getByRole("button", { name: "Record preview", exact: true })
      .isEnabled(),
    true,
  );
  await nav("Sessions");
  assert.equal(await page.locator(".session").count(), 3);
  pass(
    "opening a saved session finishes active preview capture before changing the source",
  );
  await nav("Devices");
  assert.match(await page.locator("#page").textContent(), /9f7c0001/);
  assert.equal(
    await page.getByRole("button", { name: /connect|scan/i }).count(),
    0,
  );
  await audit("desktop Devices", "companion-devices.png");
  pass("browser clearly separates protocol information from native BLE");
  await nav("Settings");
  await page.getByRole("switch", { name: "Reduce motion" }).click();
  assert.equal(
    await page
      .getByRole("switch", { name: "Reduce motion" })
      .getAttribute("aria-checked"),
    "true",
  );
  await audit("desktop Settings", "companion-settings.png");
  pass("settings are local and source boundaries visible");
  for (const width of [320, 390, 768]) {
    await page.setViewportSize({ width, height: width === 768 ? 1024 : 844 });
    for (const name of ["Monitor", "Sessions", "Devices", "Settings"]) {
      await nav(name);
      await audit(
        `${width}px ${name}`,
        width === 390 ? `mobile-${name.toLowerCase()}.png` : null,
      );
    }
  }
  await page.setViewportSize({ width: 1440, height: 1100 });
  await nav("Monitor");
  await page.getByRole("button", { name: "5 s", exact: true }).click();
  await page.getByRole("slider", { name: "Recording position" }).fill("6000");
  await page
    .getByLabel("Packet scenario", { exact: true })
    .selectOption("clean");
  await page.getByRole("button", { name: "Run check" }).click();
  await page.evaluate(
    () => (document.querySelector("#toast").textContent = ""),
  );
  await page.screenshot({
    path: path.join(root, "docs/screenshots/companion-monitor.png"),
    fullPage: true,
  });
  await nav("Sessions");
  const beforeDelete = await page.locator(".session").count();
  await page.locator("[data-delete]").first().click();
  await page
    .getByRole("button", { name: "Keep recording", exact: true })
    .click();
  assert.equal(await page.locator(".session").count(), beforeDelete);
  await page.locator("[data-delete]").first().click();
  await page
    .getByRole("button", { name: "Delete recording", exact: true })
    .click();
  await page.waitForFunction(
    (n) => document.querySelectorAll(".session").length === n,
    beforeDelete - 1,
  );
  pass("recording deletion requires a concrete dialog and updates IndexedDB");
  const blockedContext = await browser.newContext();
  await blockedContext.addInitScript(() =>
    Object.defineProperty(window, "indexedDB", { get: () => undefined }),
  );
  const blocked = await blockedContext.newPage();
  await blocked.goto(url);
  await blocked
    .getByRole("navigation", { name: "Main navigation" })
    .getByRole("button", { name: "Sessions", exact: true })
    .click();
  await blocked
    .getByText("Local recording storage is unavailable.", {
      exact: false,
    })
    .waitFor();
  await blocked
    .getByRole("button", { name: "Go to monitor", exact: false })
    .click();
  await blocked
    .getByRole("button", { name: "Save excerpt", exact: true })
    .click();
  assert.match(
    await blocked.locator("#toast").textContent(),
    /storage is unavailable/,
  );
  await blockedContext.close();
  pass(
    "unavailable IndexedDB leaves playback usable and gives a clear save message",
  );
  const offline = await context.newPage();
  await offline.goto("file://" + path.join(root, "index.html"));
  await offline.waitForFunction(
    () =>
      document.querySelector("#wave-path")?.getAttribute("d")?.length > 1000,
  );
  await offline.close();
  pass("standalone file opens offline");
  assert.deepEqual(errors, []);
  assert.ok(
    requests.every((r) => r.startsWith(url)),
    JSON.stringify(requests),
  );
  pass("no page errors or external runtime requests");
  await fs.mkdir(path.join(root, "docs/verification"), { recursive: true });
  await fs.writeFile(
    path.join(root, "docs/verification/browser-tests.json"),
    JSON.stringify(
      {
        executedAt: new Date().toISOString(),
        browser: "Chromium 134 / Playwright 1.51.1",
        checks,
        checkCount: checks.length,
        audits,
        assertions:
          "Interactions, actual IndexedDB persistence, exact exports, WCAG 2.1 AA axe checks and horizontal layout at 320, 390, 768 and 1440 px",
        pageErrors: errors,
        externalRequests: requests.filter((r) => !r.startsWith(url)),
      },
      null,
      2,
    ) + "\n",
  );
  console.log(
    `${checks.length} browser checks passed; ${audits.length} accessibility states passed.`,
  );
} finally {
  await context.close();
  await browser.close();
  await new Promise((r) => server.close(r));
}
