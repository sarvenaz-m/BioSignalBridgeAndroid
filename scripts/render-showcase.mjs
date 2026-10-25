import fs from "node:fs/promises";
import path from "node:path";
import http from "node:http";
import { chromium } from "playwright";

const root = path.resolve(import.meta.dirname, "..");
const out = path.join(root, "docs/screenshots");
await fs.mkdir(out, { recursive: true });
const html = await fs.readFile(path.join(root, "index.html"));
const server = http.createServer((request, response) => {
  response.writeHead(200, { "Content-Type": "text/html" });
  response.end(html);
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const browser = await chromium.launch({ headless: true, args: ["--no-sandbox"] });

try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1100 } });
  const page = await context.newPage();
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  await page.getByRole("button", { name: "Save excerpt", exact: true }).waitFor();
  await page.evaluate(() => document.fonts.ready);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(out, "showcase-companion-monitor.png") });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => {
    const wave = document.querySelector(".wave-panel");
    window.scrollTo(0, wave.getBoundingClientRect().top + scrollY - 18);
  });
  await page.screenshot({ path: path.join(out, "showcase-companion-waveform.png") });
  await page.getByRole("button", { name: "Save excerpt", exact: true }).click();
  await page.getByRole("navigation", { name: "Main navigation" }).getByRole("button", { name: "Sessions", exact: true }).click();
  await page.getByText("3,000", { exact: true }).first().waitFor();
  await page.evaluate(() => {
    document.querySelector("#toast").textContent = "";
    window.scrollTo(0, 0);
  });
  await page.screenshot({ path: path.join(out, "showcase-companion-sessions.png") });
  await context.close();

  const data = async (filename) => `data:image/png;base64,${(await fs.readFile(path.join(out, filename))).toString("base64")}`;
  const font = (await fs.readFile(path.join(root, "app/src/main/res/font/manrope.ttf"))).toString("base64");
  const desktop = await data("showcase-companion-monitor.png");
  const waveform = await data("showcase-companion-waveform.png");
  const sessions = await data("showcase-companion-sessions.png");
  const waveIcon = `<svg viewBox="0 0 40 40" fill="none"><path d="M3 21h9l5-13 7 25 5-15 3 3h5" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
  const poster = `<!doctype html><html><head><meta charset="utf-8"><style>
  @font-face{font-family:Manrope;src:url(data:font/ttf;base64,${font}) format('truetype');font-weight:200 800}*{box-sizing:border-box}body{margin:0;font-family:Manrope,sans-serif;color:#123c4a}.poster{width:1920px;height:1280px;position:relative;overflow:hidden;background:#f2f6f4}.grid{position:absolute;inset:0;background-image:linear-gradient(#123c4a06 1px,transparent 1px),linear-gradient(90deg,#123c4a06 1px,transparent 1px);background-size:40px 40px}.sweep{position:absolute;width:1460px;height:1460px;right:-450px;top:-400px;border-radius:50%;border:1px solid #d6e5df;box-shadow:0 0 0 100px #eaf1ed,0 0 0 101px #dce8e2,0 0 0 202px #eef4f0}.brand{position:absolute;left:80px;top:65px;display:flex;align-items:center;gap:18px;font-size:29px;font-weight:800;letter-spacing:-1px}.brandicon{display:grid;place-items:center;width:59px;height:59px;background:#123c4a;border-radius:18px;color:#9edcc7}.brandicon svg{width:43px;height:43px}.brand small{display:block;font-size:12px;letter-spacing:3px;font-weight:700;margin-top:4px;color:#557179}.version{position:absolute;right:80px;top:76px;background:white;border:1px solid #d8e4de;padding:12px 19px;border-radius:40px;font-size:16px;font-weight:700}.copy{position:absolute;left:80px;top:215px;width:540px}.kicker{font-size:14px;font-weight:800;letter-spacing:2.7px;color:#166a6c}.copy h1{font-size:78px;line-height:1.08;font-weight:750;letter-spacing:-4px;margin:26px 0}.intro{font-size:23px;line-height:1.55;color:#526970;width:500px;margin:0}.facts{display:flex;gap:12px;align-items:center;margin-top:28px}.facts span{padding:10px 14px;background:#e1eeE7;border-radius:9px;font-size:16px;font-weight:750;color:#205d5c}.features{margin-top:42px;display:grid;gap:25px}.feature{display:flex;gap:17px}.num{font-size:13px;font-weight:800;letter-spacing:1px;line-height:28px;color:#4d8780;flex:0 0 28px}.feature strong{font-size:20px;display:block;font-weight:800;margin-bottom:6px}.feature p{font-size:17px;line-height:1.5;margin:0;color:#597078;max-width:440px}.desktop{position:absolute;left:690px;top:205px;width:1150px;height:784px;border-radius:23px;background:#fff;box-shadow:0 40px 75px #153b4a25,0 5px 12px #153b4a10;overflow:hidden;border:1px solid #c6d8d1}.windowbar{height:42px;background:#fff;display:flex;align-items:center;padding:0 19px;gap:7px;border-bottom:1px solid #e5ece8}.windowbar i{height:8px;width:8px;border-radius:50%;background:#c5d6cd}.windowbar span{margin-left:13px;font-size:11px;letter-spacing:1.3px;color:#638077;font-weight:750}.desktop img{width:1150px;display:block}.phone{position:absolute;padding:10px;border-radius:37px;background:#0f3240;box-shadow:0 22px 42px #123c4a33;border:1px solid #315462}.phone .screen{border-radius:28px;overflow:hidden;background:#f4f7f5}.phone img{width:100%;display:block}.phoneone{left:1180px;top:702px;width:236px;transform:rotate(-3deg)}.phonetwo{left:1515px;top:645px;width:260px;transform:rotate(3deg)}.phonecap{font-size:10px;color:#e1eee7;text-align:center;line-height:29px;letter-spacing:1.5px;font-weight:800}.lower{position:absolute;left:80px;bottom:75px;max-width:1010px}.lowerline{height:1px;background:#cbded5;width:510px;margin-bottom:23px}.lower strong{font-size:17px;font-weight:800}.lower p{font-size:14px;color:#5e777c;line-height:1.65;margin:8px 0 0}.edition{position:absolute;right:80px;bottom:36px;font-size:12px;color:#5b777c;letter-spacing:2px}.tick{display:inline-block;width:6px;height:6px;background:#166a6c;border-radius:50%;margin-right:7px;vertical-align:2px}
  </style></head><body><div class="poster"><div class="grid"></div><div class="sweep"></div><div class="brand"><div class="brandicon">${waveIcon}</div><div>BioSignal Bridge<small>SIGNAL WORKSPACE</small></div></div><div class="version">1.0.0</div><div class="copy"><div class="kicker">ANDROID + BROWSER COMPANION</div><h1>Every sample,<br>in context.</h1><p class="intro">Receive the signal. Keep its timing.<br>Leave a record you can explore.</p><div class="facts"><span>250 Hz</span><span>3,000 samples</span><span>ECG · MLII</span></div><div class="features"><div class="feature"><div class="num">01</div><div><strong>Time you can follow</strong><p>Inspect the waveform, change the window<br>and return to a moment you marked.</p></div></div><div class="feature"><div class="num">02</div><div><strong>Sessions that stay local</strong><p>Keep samples and event notes together,<br>with the recording source in view.</p></div></div><div class="feature"><div class="num">03</div><div><strong>Ready for your analysis</strong><p>Export timestamps and sample values<br>for a reproducible Python workflow.</p></div></div></div></div><div class="desktop"><div class="windowbar"><i></i><i></i><i></i><span>BROWSER COMPANION · PUBLIC ECG PREVIEW</span></div><img src="${desktop}" alt="Actual desktop browser companion"></div><div class="phone phoneone"><div class="phonecap">WAVEFORM REVIEW</div><div class="screen"><img src="${waveform}" alt="Actual mobile browser waveform"></div></div><div class="phone phonetwo"><div class="phonecap">LOCAL SESSION LIBRARY</div><div class="screen"><img src="${sessions}" alt="Actual mobile browser session library"></div></div><div class="lower"><div class="lowerline"></div><strong><span class="tick"></span>Public data. A defined sensor interface.</strong><p>Actual browser companion views shown. ECG: MIT-BIH Arrhythmia Database, record 100.<br>PhysioNet · ODC Attribution License 1.0 · resampled from 360 Hz to 250 Hz.</p></div><div class="edition">BIO / 01</div></div></body></html>`;
  const render = await browser.newPage({ viewport: { width: 1920, height: 1280 }, deviceScaleFactor: 1 });
  await render.setContent(poster);
  await render.evaluate(() => document.fonts.ready);
  await render.screenshot({ path: path.join(out, "BioSignalBridge-Showcase.png") });
  console.log("Rendered docs/screenshots/BioSignalBridge-Showcase.png from actual browser captures.");
} finally {
  await browser.close();
  await new Promise((resolve) => server.close(resolve));
}
