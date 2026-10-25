import fs from "node:fs/promises";
import path from "node:path";
import { chromium } from "playwright";

const root = path.resolve(import.meta.dirname, "..");
const images = path.join(root, "docs/screenshots");
const font = (await fs.readFile(path.join(root, "app/src/main/res/font/manrope.ttf"))).toString("base64");
const views = [
  ["monitor", "MONITOR", "The waveform in context", "Public ECG preview · time window · event notes"],
  ["sessions", "SESSIONS", "A useful local record", "Session library · review · deliberate export"],
  ["devices", "DEVICES", "A defined sensor interface", "Compatible BLE source · explicit connection"],
  ["settings", "SETTINGS", "Clear sources and boundaries", "Public data · local storage · independent work"],
];
const panels = [];
for (const [name, label, title, detail] of views) {
  const image = (await fs.readFile(path.join(images, `android-${name}.png`))).toString("base64");
  panels.push(`<section class="view"><div class="frame"><div class="view-label"><span></span>${label}</div><div class="screen"><img alt="Actual ${name} Compose view" src="data:image/png;base64,${image}"></div></div><h2>${title}</h2><p>${detail}</p></section>`);
}
const page = `<!doctype html><html><head><meta charset="utf-8"><style>
@font-face{font-family:Manrope;src:url(data:font/ttf;base64,${font}) format('truetype');font-weight:200 800}*{box-sizing:border-box}body{margin:0;font-family:Manrope,sans-serif;color:#123c4a}.poster{width:1920px;height:1280px;position:relative;overflow:hidden;background:#f2f6f4}.grid{position:absolute;inset:0;background-image:linear-gradient(#123c4a05 1px,transparent 1px),linear-gradient(90deg,#123c4a05 1px,transparent 1px);background-size:40px 40px}.accent{position:absolute;width:1300px;height:1300px;border-radius:50%;background:#eaf1ed;right:-500px;top:-750px;box-shadow:0 0 0 100px #edf3ef,0 0 0 101px #dce8e2,0 0 0 202px #f0f5f1}.brand{position:absolute;left:85px;top:56px;display:flex;align-items:center;gap:15px;font-size:26px;line-height:1.15;font-weight:800;letter-spacing:-.8px}.brandicon{width:54px;height:54px;display:grid;place-items:center;border-radius:17px;background:#123c4a;color:#9edcc7}.brandicon svg{width:39px;height:39px}.brand small{display:block;font-size:11px;letter-spacing:2.8px;margin-top:6px;color:#58737c}.edition{position:absolute;right:85px;top:67px;padding:11px 18px;border-radius:40px;background:#fff;border:1px solid #d9e5df;font-size:14px;font-weight:800;letter-spacing:1.2px}.headline{position:absolute;left:85px;top:151px}.headline h1{font-size:61px;line-height:1.12;font-weight:750;letter-spacing:-2.7px;margin:0}.headline p{font-size:21px;line-height:1.5;color:#526f78;margin:14px 0 0}.views{position:absolute;left:85px;right:85px;top:298px;display:flex;justify-content:space-between;align-items:flex-start;gap:50px}.view{flex:0 0 375px;text-align:left}.frame{width:350px;margin:auto;border-radius:36px;padding:10px;background:#103441;box-shadow:0 20px 35px #123c4a1f,0 3px 8px #123c4a17;border:1px solid #335765}.view-label{height:28px;display:flex;justify-content:center;align-items:center;gap:7px;color:#d0e5de;font-size:10px;letter-spacing:1.8px;font-weight:800}.view-label span{width:4px;height:4px;border-radius:50%;background:#88d5be}.screen{border-radius:25px;overflow:hidden;background:#f3f6f5}.screen img{width:100%;height:auto;display:block}.view h2{font-size:22px;line-height:1.3;font-weight:800;letter-spacing:-.6px;margin:22px 0 8px;text-align:center}.view p{font-size:14px;line-height:1.5;color:#59747a;margin:0;text-align:center;white-space:nowrap}.bottom{position:absolute;bottom:46px;left:85px;right:85px;border-top:1px solid #ccdcd4;padding-top:20px;display:flex;justify-content:space-between;align-items:center;font-size:13px;line-height:1.6;color:#5b747c}.bottom strong{color:#174a52;font-weight:800;letter-spacing:.1px}.bottom .source{text-align:right;font-size:12px}.dot{display:inline-block;width:6px;height:6px;border-radius:50%;background:#166a6c;margin-right:8px;vertical-align:1px}
</style></head><body><div class="poster"><div class="grid"></div><div class="accent"></div><div class="brand"><div class="brandicon"><svg viewBox="0 0 40 40" fill="none"><path d="M3 21h9l5-13 7 25 5-15 3 3h5" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg></div><div>BioSignal Bridge<small>SIGNAL WORKSPACE</small></div></div><div class="edition">ANDROID · 1.0.0</div><div class="headline"><h1>Receive. Record. Revisit.</h1><p>One workspace for the signal, its timing and the session you keep.</p></div><div class="views">${panels.join("")}</div><div class="bottom"><div><strong><span class="dot"></span>Native Compose views rendered with Robolectric</strong><br>Actual application screens · no physical sensor is connected in these views.</div><div class="source">Preview ECG: MIT-BIH Arrhythmia Database · Record 100 · MLII<br>PhysioNet · 360 Hz source resampled to 250 Hz · ODC Attribution License 1.0</div></div></div></body></html>`;
const browser = await chromium.launch({ headless: true, args: ["--no-sandbox"] });
try {
  const render = await browser.newPage({ viewport: { width: 1920, height: 1280 }, deviceScaleFactor: 1 });
  await render.setContent(page);
  await render.evaluate(() => document.fonts.ready);
  await render.screenshot({ path: path.join(images, "BioSignalBridge-Android.png") });
  console.log("Rendered docs/screenshots/BioSignalBridge-Android.png from actual native Compose captures.");
} finally {
  await browser.close();
}
