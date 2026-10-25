import fs from "node:fs";
import path from "node:path";
const root = path.resolve(import.meta.dirname, "..");
const read = (p) => fs.readFileSync(path.join(root, p), "utf8");
const domain = read("demo/src/domain.mjs").replaceAll(
  /export (?=(?:const|function|class))/g,
  "",
);
const storage = read("demo/src/storage.mjs").replaceAll(/export (?=(?:const|function|class))/g, "");
const app = read("demo/src/app.mjs")
  .replace(/^import[\s\S]*?from ['"]\.\/(?:domain|storage)\.mjs['"];\n/gm, "")
  .replace(
    "__BIO_SIGNAL_PREVIEW_DATA__",
    JSON.stringify(JSON.parse(read("data/preview.json"))),
  );
const fontPath = path.join(root, "app/src/main/res/font/manrope.ttf");
const font = fs.existsSync(fontPath)
  ? `@font-face{font-family:Manrope;src:url(data:font/ttf;base64,${fs.readFileSync(fontPath).toString("base64")}) format('truetype');font-weight:200 800;font-style:normal;font-display:swap}`
  : "";
let html = read("demo/src/template.html")
  .replace("/*__FONT__*/", font)
  .replace("/*__STYLE__*/", read("demo/src/style.css") + "\n" + read("demo/src/workspace.css"))
  .replace("/*__PROVENANCE__*/", () => JSON.stringify(JSON.parse(read("data/provenance.json"))))
  .replace("/*__SCRIPT__*/", () => domain + "\n" + storage + "\n" + app);
fs.writeFileSync(path.join(root, "index.html"), html);
fs.writeFileSync(path.join(root, "demo/BioSignalBridge.html"), html);
console.log(
  `Built offline demo: ${Buffer.byteLength(html).toLocaleString()} bytes.`,
);
