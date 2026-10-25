import {test} from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import vm from "node:vm";
import {parseHTML} from "linkedom";
import {IDBFactory} from "fake-indexeddb";

const html = await fs.readFile(new URL("../../index.html",import.meta.url),"utf8");
const source = html.match(/<script type="module">([\s\S]*?)<\/script>/)[1];
const settle = async predicate => {
  for (let i=0;i<200;i++) { if (predicate()) return; await new Promise(resolve=>setTimeout(resolve,5)); }
  assert.fail("The workspace did not reach the expected state.");
};
async function workspace(storage=true) {
  const {window,document} = parseHTML(html);
  window.scrollTo=()=>{};
  const dialog=document.querySelector("#dialog");
  dialog.showModal=()=>{dialog.open=true;}; dialog.close=()=>{dialog.open=false;};
  const memory=new Map();
  const context=vm.createContext({window,document,console,performance,URL,Blob,
    indexedDB:storage ? new IDBFactory() : undefined,
    localStorage:{getItem:key=>memory.get(key)??null,setItem:(key,value)=>memory.set(key,value)},
    requestAnimationFrame:()=>0,
    setTimeout:(fn,delay)=>{const timer=setTimeout(fn,delay);timer.unref();return timer;},clearTimeout,
  });
  vm.runInContext(source,context);
  const read=expression=>vm.runInContext(expression,context);
  await settle(()=>read("state.storageReady || !!state.storageError"));
  return {window,document,read,nav:name=>document.querySelector(`[data-nav="${name}"]`).click()};
}

test("offline bundle boots with a waveform, overview and measured window",async()=>{
  const {document,read}=await workspace();
  assert.ok(document.querySelector("#wave-path").getAttribute("d").length>1000);
  assert.ok(document.querySelector("#overview-path").getAttribute("d").length>1000);
  assert.match(document.querySelector("#window-inspection").textContent,/Observed rate250.0Hz/);
  document.querySelector('[data-zoom="2"]').click();
  assert.equal(read("state.zoom"),2);
  assert.match(document.querySelector("#window-label").textContent,/4.00 s – 6.00 s/);
});

test("all four destinations render without a browser service",async()=>{
  const {document,nav}=await workspace();
  for(const name of ["Sessions","Devices","Settings","Monitor"]){
    nav(name); assert.ok(document.querySelector("#page").textContent.trim().length>100);
    assert.equal(document.querySelector('[aria-current="page"]').dataset.nav,name);
  }
});

test("save, rename, search, review and delete retain a coherent local session",async()=>{
  const {window,document,read,nav}=await workspace();
  document.querySelector("#save-excerpt").click();
  await settle(()=>read("state.sessions.length===1"));
  nav("Sessions");
  document.querySelector("[data-rename]").click();
  document.querySelector("#session-name").value="Bench recording · sensor adjusted";
  document.querySelector("#dialog form").dispatchEvent(new window.Event("submit",{cancelable:true}));
  await settle(()=>document.querySelector(".session h3")?.textContent==="Bench recording · sensor adjusted");
  const search=document.querySelector("#session-search");
  search.value="not found"; search.dispatchEvent(new window.Event("input"));
  assert.match(document.querySelector("#session-results").textContent,/No matching recordings/);
  search.value="sensor adjusted";search.dispatchEvent(new window.Event("input"));
  document.querySelector("[data-review]").click();
  await settle(()=>!!document.querySelector(".review-banner"));
  assert.match(document.querySelector(".review-banner").textContent,/Bench recording/);
  assert.equal(read("state.review.samples.length"),3000);
  nav("Sessions"); document.querySelector("[data-delete]").click();
  document.querySelector("#confirm-delete").click();
  await settle(()=>read("state.sessions.length===0"));
  assert.equal(read("state.review"),null);
});

test("an unavailable recording database is visible and never claims to save",async()=>{
  const {document,read,nav}=await workspace(false);
  document.querySelector("#save-excerpt").click();
  assert.equal(read("state.sessions.length"),0);
  assert.match(document.querySelector("#toast").textContent,/unavailable/i);
  nav("Sessions"); assert.match(document.querySelector("#storage-status").textContent,/unavailable/i);
});

test("keyboard help opens a labelled list and closes cleanly",async()=>{
  const {document}=await workspace();
  document.querySelector("#shortcut-help").click();
  assert.equal(document.querySelector("#dialog").open,true);
  assert.match(document.querySelector("#dialog").textContent,/Play or pause/);
  document.querySelector("#close-help").click();
  assert.equal(document.querySelector("#dialog").open,false);
});

test("monitor keyboard controls move playback and keep dialog input separate",async()=>{
  const {window,document,read}=await workspace();
  const key=(code,value)=>{
    const event=new window.Event("keydown",{bubbles:true,cancelable:true});
    Object.assign(event,{code,key:value});document.body.dispatchEvent(event);
  };
  key("Space"," ");assert.equal(read("state.playing"),true);
  key("ArrowRight","ArrowRight");assert.equal(read("state.playing"),false);
  assert.equal(read("state.cursorNs"),7e9);
  key("KeyM","m");assert.equal(document.querySelector("#dialog").open,true);
  assert.equal(document.querySelector("#dialog").getAttribute("aria-label"),"Event marker");
  key("Space"," ");assert.equal(read("state.playing"),false);
});
