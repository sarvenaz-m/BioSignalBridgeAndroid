import {test} from "node:test";
import assert from "node:assert/strict";
import {inspectWindow, findSessions, validateSessionName} from "../src/domain.mjs";

const channel = {id:0,name:"Test channel",unit:"mV",scalePerCount:.5};
const sample = (timeNs,raw,gapBefore=false,channel=0) => ({timeNs,raw,gapBefore,channel});
const close = (actual,expected) => assert.ok(Math.abs(actual-expected)<1e-10,`${actual} differs from ${expected}`);

test("empty inspection preserves absence of measurements", () => {
  const result=inspectWindow([],channel);
  assert.equal(result.sampleCount,0); assert.equal(result.mean,null);
  assert.equal(result.observedRateHz,null); assert.equal(result.segments,0);
});
test("known calibrated signal has correct moments and timing", () => {
  const result=inspectWindow([sample(0,-2),sample(4e6,0),sample(8e6,2)],channel);
  close(result.minimum,-1); close(result.maximum,1); close(result.mean,0);
  close(result.rms,Math.sqrt(2/3)); close(result.standardDeviation,result.rms);
  close(result.observedRateHz,250); close(result.intervalJitterUs,0);
});
test("gaps and interleaved channels do not corrupt the rate", () => {
  const result=inspectWindow([sample(0,1),sample(1,12,false,1),sample(4e6,2),sample(900e6,3,true),sample(904e6,4)],channel);
  assert.equal(result.sampleCount,4); assert.equal(result.gapMarkers,1); assert.equal(result.segments,2);
  close(result.observedRateHz,250);
});
test("equal timestamps are reported without inventing an infinite rate", () => {
  const result=inspectWindow([sample(4,1),sample(4,2)],channel);
  assert.equal(result.duplicateTimestamps,1); assert.equal(result.observedRateHz,null);
});
test("negative calibration preserves ordered amplitudes and rail counts", () => {
  const result=inspectWindow([sample(0,-32768),sample(4,32767)],{...channel,scalePerCount:-2});
  assert.equal(result.minimum,-65534); assert.equal(result.maximum,65536); assert.equal(result.railSamples,2);
});
test("large finite scales do not overflow intermediate squares", () => {
  const result=inspectWindow([sample(0,-32768),sample(4,32767)],{...channel,scalePerCount:1e300});
  assert.ok(Number.isFinite(result.rms)); assert.ok(Number.isFinite(result.standardDeviation));
});
test("window bounds exclude neighbouring measurements", () => {
  const result=inspectWindow([sample(0,100),sample(4e6,-2),sample(8e6,2),sample(12e6,-100)],channel,4e6,8e6);
  assert.equal(result.sampleCount,2); assert.equal(result.minimum,-1); assert.equal(result.maximum,1);
});
test("invalid channel timing and values are rejected", () => {
  assert.throws(()=>inspectWindow([sample(20,1),sample(10,2)],channel));
  assert.throws(()=>inspectWindow([sample(1,32768)],channel));
  assert.throws(()=>inspectWindow([sample(1,1)],{...channel,scalePerCount:0}));
});
const sessions=[
 {id:"a",name:"Morning",source:"PREVIEW",startedAtEpochMs:10,samples:[sample(0,1),sample(10,2)],markers:[{label:"Sensor adjusted"}]},
 {id:"b",name:"Afternoon",source:"PREVIEW",startedAtEpochMs:20,samples:[sample(0,1),sample(50,2)],markers:[]},
];
test("session search includes event notes and leaves the library unchanged", () => {
  assert.deepEqual(findSessions(sessions,"ADJUSTED").map(s=>s.id),["a"]);
  assert.deepEqual(findSessions(sessions,"","name").map(s=>s.id),["b","a"]);
  assert.deepEqual(findSessions(sessions,"","longest").map(s=>s.id),["b","a"]);
  assert.deepEqual(sessions.map(s=>s.id),["a","b"]);
});
test("session names accept ordinary Unicode and reject empty or oversized input", () => {
  assert.equal(validateSessionName("  ثبت آزمایشی  "),"ثبت آزمایشی");
  assert.throws(()=>validateSessionName(" "));
  assert.throws(()=>validateSessionName("a".repeat(121)));
});
