import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import {
  encodePacket,
  decodePacket,
  Processor,
  makePreview,
  qualityScenario,
  exportCsv,
  exportMetadata,
  CSV_HEADER,
  pathFor,
  validateMarker,
} from "../src/domain.mjs";
const p = {
  sequence: 65535,
  tickUs: 4294960000,
  periodUs: 4000,
  channel: 0,
  samples: [-32768, 0, 32767],
};
test("20-byte binary frame preserves signed samples and little endian fields", () => {
  const b = encodePacket(p);
  assert.equal(b.length, 20);
  assert.equal(b[2], 255);
  assert.deepEqual(decodePacket(b), p);
});
test("wrong magic, version and size are rejected", () => {
  for (const b of [
    new Uint8Array(19),
    new Uint8Array(20),
    Uint8Array.from([0xb1, 2, ...new Array(18).fill(0)]),
  ])
    assert.throws(() => decodePacket(b));
});
test("unused sample bytes must be zero", () => {
  const b = encodePacket(p);
  b[19] = 1;
  assert.throws(() => decodePacket(b));
});
test("period zero and invalid sample counts rejected", () => {
  const b = encodePacket(p);
  b[8] = 0;
  b[9] = 0;
  assert.throws(() => decodePacket(b));
  const c = encodePacket(p);
  c[11] = 5;
  assert.throws(() => decodePacket(c));
});
test("encoder validates raw values and channel bounds", () => {
  assert.throws(() => encodePacket({ ...p, samples: [40000] }));
  assert.throws(() => encodePacket({ ...p, channel: -1 }));
});
test("sample sequence and clock wrap remain monotonic", () => {
  const proc = new Processor();
  let a = proc.accept(
    encodePacket({ ...p, tickUs: 0xffffe000, samples: [1] }),
    0,
  );
  let b = proc.accept(
    encodePacket({ ...p, sequence: 0, tickUs: 0x1000, samples: [2] }),
    1e9,
  );
  assert.equal(b.stats.lostPackets, 0);
  assert.equal(b.samples[0].timeNs - a.samples[0].timeNs, 0x3000 * 1000);
});
test("clean scenario has twelve accepted packets and no faults", () => {
  const r = qualityScenario("clean");
  assert.deepEqual(r.stats, {
    receivedPackets: 12,
    acceptedPackets: 12,
    malformedPackets: 0,
    lostPackets: 0,
    duplicatePackets: 0,
    outOfOrderPackets: 0,
    clockResets: 0,
  });
  assert.equal(r.samples.length, 48);
});
test("missing packets counted once and mark next sample gap", () => {
  const r = qualityScenario("loss");
  assert.equal(r.stats.receivedPackets, 10);
  assert.equal(r.stats.lostPackets, 2);
  assert.equal(r.samples.filter((s) => s.gapBefore).length, 1);
});
test("duplicate packet omitted from samples", () => {
  const r = qualityScenario("duplicate");
  assert.equal(r.stats.receivedPackets, 13);
  assert.equal(r.stats.duplicatePackets, 1);
  assert.equal(r.samples.length, 48);
});
test("out-of-order delayed packet never rewinds samples", () => {
  const r = qualityScenario("reorder");
  assert.equal(r.stats.outOfOrderPackets, 1);
  assert.equal(r.stats.lostPackets, 1);
  assert.ok(
    r.samples.every((s, i) => i === 0 || s.timeNs > r.samples[i - 1].timeNs),
  );
});
test("two coherent packets confirm a reset of both counters", () => {
  const r = qualityScenario("reset");
  assert.equal(r.stats.clockResets, 1);
  assert.equal(r.stats.acceptedPackets, 12);
  assert.equal(r.stats.lostPackets, 0);
  assert.equal(r.events[6].status, "CLOCK_RESET_PENDING");
});
test("malformed frame never emits samples", () => {
  const r = qualityScenario("malformed");
  assert.equal(r.stats.malformedPackets, 1);
  assert.equal(r.stats.acceptedPackets, 12);
  assert.equal(r.samples.length, 48);
});
test("public preview has 3000 calibrated samples and measured packet counts", () => {
  const r = makePreview(
    JSON.parse(
      fs.readFileSync(new URL("../../data/preview.json", import.meta.url)),
    ),
  );
  assert.equal(r.samples.length, 3000);
  assert.equal(r.stats.acceptedPackets, 750);
  assert.equal(r.samples[0].timeNs, 0);
  assert.equal(r.samples.at(-1).timeNs, 11996000000);
  assert.equal(r.channels[0].scalePerCount, 0.005);
});
const session = {
  id: "session-test",
  source: "PREVIEW",
  startedAtEpochMs: 1,
  channels: [{ id: 0, name: "ECG · MLII", unit: "mV", scalePerCount: 0.005 }],
  samples: [
    {
      channel: 0,
      sequence: 2,
      timeNs: 500000000,
      deviceTickUs: 0,
      raw: -4,
      gapBefore: false,
    },
    {
      channel: 0,
      sequence: 2,
      timeNs: 504000000,
      deviceTickUs: 4000,
      raw: 12,
      gapBefore: true,
    },
  ],
  markers: [{ id: "a", timeNs: 0, label: "Start" }],
  stats: qualityScenario("clean").stats,
};
test("CSV uses exact analysis header, relative nanoseconds and raw integers", () => {
  const rows = exportCsv(session).trim().split("\n");
  assert.equal(rows[0], CSV_HEADER);
  assert.equal(rows[1], '"session-test",0,2,0,0,-4,false');
  assert.equal(rows[2], '"session-test",0,2,4000000,4000,12,true');
});
test("metadata carries source, channel calibration, packet stats and marker time", () => {
  const r = exportMetadata(session);
  assert.equal(r.schema, "biosignal-bridge.session/v1");
  assert.equal(r.source, "PREVIEW");
  assert.equal(r.timeUnit, "ns");
  assert.equal(r.originNs, 0);
  assert.equal(r.durationNs, 4000000);
  assert.equal(r.sampleCount, 2);
  assert.deepEqual(r.channels, session.channels);
});
test("SVG trace starts a new path after a flagged gap", () => {
  const d = pathFor(session.samples, 500000000, 504000000);
  assert.equal((d.match(/M/g) || []).length, 2);
  assert.equal((d.match(/L/g) || []).length, 0);
});
test("marker validation retains text and rejects blank or excessive input", () => {
  assert.equal(validateMarker("  Repositioned  "), "Repositioned");
  assert.equal(validateMarker("<check>"), "<check>");
  assert.throws(() => validateMarker(" "));
  assert.throws(() => validateMarker("x".repeat(121)));
});

const fixtures = JSON.parse(
  fs.readFileSync(
    new URL(
      "../../core/signal/src/test/resources/processor-scenarios.json",
      import.meta.url,
    ),
  ),
).scenarios;
for (const fixture of fixtures)
  test("native/browser parity: " + fixture.name, () => {
    const processor = new Processor(),
      statuses = [],
      samples = [];
    for (const f of fixture.frames) {
      const r = processor.accept(encodePacket(f.packet), f.hostTimeNs);
      statuses.push(r.status);
      samples.push(...r.samples);
    }
    assert.deepEqual(statuses, fixture.expectedStatuses);
    assert.deepEqual(processor.stats, fixture.expectedStats);
    assert.deepEqual(
      samples.map((s) => s.timeNs),
      fixture.expectedSampleTimeNs,
    );
    assert.deepEqual(
      samples.flatMap((s, i) => (s.gapBefore ? [i] : [])),
      fixture.expectedGapIndices,
    );
  });
