export const SCHEMA = "biosignal-bridge.session/v1";
export const CSV_HEADER =
  "session_id,channel,sequence,timestamp_ns,device_tick_us,raw_value,gap_before";

/** Constant-memory value and timing summary; independent of the chart's reduced path. */
export function inspectWindow(samples, channel, startNs = 0, endNs = Number.MAX_SAFE_INTEGER) {
  const scale = channel.scalePerCount;
  if (!Number.isFinite(scale) || scale === 0 || !Number.isFinite(scale * 32768))
    throw new Error("The channel scale must be finite and nonzero.");
  let count = 0, minimum = Infinity, maximum = -Infinity, mean = 0, m2 = 0, squareMean = 0;
  let previous = null, intervals = 0, intervalMean = 0, intervalM2 = 0;
  let gaps = 0, segments = 0, equalTimes = 0, rails = 0;
  const magnitude = Math.abs(scale) * 32768, sign = Math.sign(scale);
  for (const sample of samples) {
    if (sample.channel !== channel.id || sample.timeNs < startNs || sample.timeNs > endNs) continue;
    if (!Number.isSafeInteger(sample.timeNs) || sample.timeNs < 0 ||
        !Number.isInteger(sample.raw) || sample.raw < -32768 || sample.raw > 32767 ||
        (previous !== null && sample.timeNs < previous))
      throw new Error("The window contains invalid values or backwards channel timestamps.");
    const value = sample.raw / 32768 * sign;
    count++;
    minimum = Math.min(minimum, value); maximum = Math.max(maximum, value);
    const delta = value - mean;
    mean += delta / count; m2 += delta * (value - mean);
    squareMean += (value * value - squareMean) / count;
    if (sample.gapBefore) gaps++;
    if (previous === null || sample.gapBefore) segments++;
    if (sample.raw === -32768 || sample.raw === 32767) rails++;
    if (previous !== null) {
      const interval = sample.timeNs - previous;
      if (interval === 0) equalTimes++;
      if (!sample.gapBefore && interval > 0) {
        intervals++;
        const delta = interval - intervalMean;
        intervalMean += delta / intervals;
        intervalM2 += delta * (interval - intervalMean);
      }
    }
    previous = sample.timeNs;
  }
  return {
    sampleCount: count,
    minimum: count ? minimum * magnitude : null,
    maximum: count ? maximum * magnitude : null,
    mean: count ? mean * magnitude : null,
    rms: count ? Math.sqrt(Math.max(0, squareMean)) * magnitude : null,
    standardDeviation: count ? Math.sqrt(Math.max(0, m2 / count)) * magnitude : null,
    observedRateHz: intervals ? 1e9 / intervalMean : null,
    intervalJitterUs: intervals ? Math.sqrt(Math.max(0, intervalM2 / intervals)) / 1000 : null,
    gapMarkers: gaps, segments, duplicateTimestamps: equalTimes, railSamples: rails,
  };
}

export function validateSessionName(value) {
  const name = String(value).trim();
  if (!name || name.length > 120) throw new Error("Choose a session name between 1 and 120 characters.");
  return name;
}

export function findSessions(sessions, query = "", sort = "newest") {
  const needle = query.trim().toLocaleLowerCase();
  const result = sessions.filter(session => !needle || [session.name, session.source, ...session.markers.map(m => m.label)]
    .some(value => String(value).toLocaleLowerCase().includes(needle)));
  return result.sort(sort === "name" ? (a, b) => a.name.localeCompare(b.name) :
    sort === "longest" ? (a, b) => (b.samples.at(-1)?.timeNs || 0) - (a.samples.at(-1)?.timeNs || 0) :
    (a, b) => b.startedAtEpochMs - a.startedAtEpochMs);
}
export function decodePacket(bytes) {
  if (
    !(bytes instanceof Uint8Array) ||
    bytes.length !== 20 ||
    bytes[0] !== 0xb1 ||
    bytes[1] !== 1
  )
    throw new Error("The packet must be a 20-byte BioSignal Bridge v1 frame.");
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const periodUs = v.getUint16(8, true),
    count = bytes[11];
  if (count < 1 || count > 4 || periodUs === 0)
    throw new Error("The sample count or sampling period is invalid.");
  for (let i = 12 + count * 2; i < 20; i++)
    if (bytes[i] !== 0) throw new Error("Unused sample bytes must be zero.");
  return {
    sequence: v.getUint16(2, true),
    tickUs: v.getUint32(4, true),
    periodUs,
    channel: bytes[10],
    samples: Array.from({ length: count }, (_, i) =>
      v.getInt16(12 + 2 * i, true),
    ),
  };
}
export function encodePacket(p) {
  if (
    !Number.isInteger(p.sequence) ||
    p.sequence < 0 ||
    p.sequence > 65535 ||
    !Number.isInteger(p.tickUs) ||
    p.tickUs < 0 ||
    p.tickUs > 0xffffffff ||
    !Number.isInteger(p.periodUs) ||
    p.periodUs < 1 ||
    p.periodUs > 65535 ||
    !Number.isInteger(p.channel) ||
    p.channel < 0 ||
    p.channel > 255 ||
    !Array.isArray(p.samples) ||
    p.samples.length < 1 ||
    p.samples.length > 4 ||
    p.samples.some((x) => !Number.isInteger(x) || x < -32768 || x > 32767)
  )
    throw new Error("Invalid packet fields.");
  const b = new Uint8Array(20),
    v = new DataView(b.buffer);
  b[0] = 0xb1;
  b[1] = 1;
  v.setUint16(2, p.sequence, true);
  v.setUint32(4, p.tickUs, true);
  v.setUint16(8, p.periodUs, true);
  b[10] = p.channel;
  b[11] = p.samples.length;
  p.samples.forEach((x, i) => v.setInt16(12 + 2 * i, x, true));
  return b;
}
export const emptyStats = () => ({
  receivedPackets: 0,
  acceptedPackets: 0,
  malformedPackets: 0,
  lostPackets: 0,
  duplicatePackets: 0,
  outOfOrderPackets: 0,
  clockResets: 0,
});
export class Processor {
  constructor() {
    this.stats = emptyStats();
    this.last = null;
    this.relative = 0;
    this.anchor = 0;
    this.lastNs = -1;
    this.channelLastNs = new Map();
    this.gapGeneration = 0;
    this.channelGapGeneration = new Map();
    this.pending = null;
    this.gap = false;
  }
  accept(bytes, hostNs) {
    this.stats.receivedPackets++;
    let p;
    try {
      p = decodePacket(bytes);
    } catch {
      return this.result("MALFORMED", [], () => this.stats.malformedPackets++);
    }
    if (!this.last) return this.first(p, hostNs, false);
    if (this.pending) {
      const c = this.pending;
      this.pending = null;
      const sd = (p.sequence - c.p.sequence) & 65535,
        td = (p.tickUs - c.p.tickUs) >>> 0;
      if (
        sd >= 1 &&
        sd <= 16 &&
        td >= 0 &&
        td <= 2e6 &&
        (td > 0 || p.channel !== c.p.channel) &&
        hostNs >= c.host
      ) {
        this.stats.clockResets++;
        this.gapGeneration++;
        const a = this.first(c.p, c.host, true).samples;
        const b = this.forward(p, sd, td).samples;
        return this.result("ACCEPTED", [...a, ...b]);
      }
      this.stats.outOfOrderPackets++;
    }
    const sd = (p.sequence - this.last.sequence) & 65535,
      td = (p.tickUs - this.last.tickUs) >>> 0;
    if (sd === 0)
      return this.result("DUPLICATE", [], () => this.stats.duplicatePackets++);
    const back = td > 0x7fffffff,
      reset = back && this.last.tickUs - p.tickUs >= 2e6;
    if (sd >= 1 && sd <= 32767) {
      if (reset) {
        this.stats.clockResets++;
        this.gapGeneration++;
        return this.first(p, hostNs, true);
      }
      if (!back) return this.forward(p, sd, td);
    } else if (reset && p.sequence <= 15 && p.tickUs <= 5e6) {
      this.pending = { p, host: hostNs };
      return this.result("CLOCK_RESET_PENDING");
    }
    return this.result(
      "OUT_OF_ORDER",
      [],
      () => this.stats.outOfOrderPackets++,
    );
  }
  result(status, samples = [], action = () => {}) {
    action();
    return { status, samples, stats: { ...this.stats } };
  }
  first(p, host, reset) {
    this.last = p;
    this.relative = 0;
    this.anchor = Math.max(
      host - (p.samples.length - 1) * p.periodUs * 1000,
      reset ? this.lastNs + 1 : 0,
    );
    return this.emit(p);
  }
  forward(p, sd, td) {
    if (sd > 1) {
      this.stats.lostPackets += sd - 1;
      this.gapGeneration++;
    } else if (td > 2e6) this.gapGeneration++;
    this.relative += td;
    this.last = p;
    return this.emit(p);
  }
  emit(p) {
    this.stats.acceptedPackets++;
    const prior = this.channelLastNs.get(p.channel) ?? -1,
      projected = this.anchor + this.relative * 1000,
      first = Math.max(projected, prior + 1),
      gap =
        this.gapGeneration > (this.channelGapGeneration.get(p.channel) || 0) ||
        projected <= prior;
    this.channelGapGeneration.set(p.channel, this.gapGeneration);
    const samples = p.samples.map((raw, i) => ({
      timeNs: first + i * p.periodUs * 1000,
      deviceTickUs: (p.tickUs + i * p.periodUs) >>> 0,
      channel: p.channel,
      sequence: p.sequence,
      raw,
      gapBefore: i === 0 && gap,
    }));
    this.lastNs = Math.max(this.lastNs, samples.at(-1).timeNs);
    this.channelLastNs.set(p.channel, samples.at(-1).timeNs);
    return this.result("ACCEPTED", samples);
  }
}
export function makePreview(data) {
  const raw = data.samples || data.rawSamples || data.raw || [];
  const counts = raw.map((x) =>
    typeof x === "number" ? x : (x.raw ?? x.value),
  );
  const hz = data.sampleRateHz || data.sampleRate || 250,
    periodUs = Math.round(1e6 / hz),
    processor = new Processor(),
    samples = [];
  for (let i = 0; i < counts.length; i += 4) {
    const p = {
      sequence: (i / 4) & 65535,
      tickUs: i * periodUs,
      periodUs,
      channel: 0,
      samples: counts.slice(i, i + 4),
    };
    samples.push(
      ...processor.accept(
        encodePacket(p),
        (i + p.samples.length - 1) * periodUs * 1000,
      ).samples,
    );
  }
  return {
    samples,
    stats: { ...processor.stats },
    channels: [
      {
        id: 0,
        name: "ECG · MLII",
        unit: "mV",
        scalePerCount: data.scalePerCount || 0.005,
      },
    ],
    sampleRateHz: hz,
  };
}
export function qualityScenario(kind) {
  const processor = new Processor(),
    out = [],
    events = [];
  const base = Array.from({ length: 12 }, (_, i) => ({
    sequence: 100 + i,
    tickUs: 3e6 + i * 16000,
    periodUs: 4000,
    channel: 0,
    samples: [i * 4, i * 4 + 1, i * 4 + 2, i * 4 + 3],
  }));
  let frames = base.map((p) => ({ p, note: "" }));
  if (kind === "loss") frames = frames.filter((_, i) => i !== 4 && i !== 5);
  if (kind === "duplicate")
    frames.splice(5, 0, { p: base[4], note: "Same sequence received twice" });
  if (kind === "reorder") [frames[5], frames[6]] = [frames[6], frames[5]];
  if (kind === "reset")
    frames = frames
      .slice(0, 6)
      .concat(
        base
          .slice(6)
          .map((p, i) => ({
            p: { ...p, sequence: i, tickUs: i * 16000 },
            note: i === 0 ? "Both device counters restart" : "",
          })),
      );
  if (kind === "malformed") frames.splice(4, 0, { p: base[4], bad: true });
  frames.forEach((f, i) => {
    const bytes = encodePacket(f.p);
    if (f.bad) bytes[0] = 0;
    const r = processor.accept(bytes, (i + 1) * 16e6);
    out.push(...r.samples);
    events.push({ sequence: f.p.sequence, status: r.status, note: f.note });
  });
  return { stats: { ...processor.stats }, samples: out, events };
}
export function exportMetadata(session) {
  const origin = session.samples.length
    ? session.samples.reduce((m, s) => Math.min(m, s.timeNs), Infinity)
    : 0;
  return {
    schema: SCHEMA,
    sessionId: session.id,
    source: session.source,
    startedAtEpochMs: session.startedAtEpochMs,
    timeUnit: "ns",
    originNs: 0,
    channels: session.channels,
    markers: session.markers.map((m) => ({
      ...m,
      timeNs: Math.max(0, m.timeNs - origin),
    })),
    stats: session.stats,
    name: session.name,
    inspection: {
      schema: "biosignal-bridge.inspection/v1",
      scope: "whole-recording",
      channels: session.channels.map(channel => ({channel:channel.id,...inspectWindow(session.samples,channel)})),
    },
    sampleCount: session.samples.length,
    durationNs: session.samples.length
      ? session.samples.reduce((m, s) => Math.max(m, s.timeNs), 0) - origin
      : 0,
  };
}
function csvCell(s) {
  return '"' + String(s).replaceAll('"', '""') + '"';
}
export function exportCsv(session) {
  const origin = session.samples.length
    ? session.samples.reduce((m, s) => Math.min(m, s.timeNs), Infinity)
    : 0;
  return (
    CSV_HEADER +
    "\n" +
    session.samples
      .map((s) =>
        [
          csvCell(session.id),
          s.channel,
          s.sequence,
          s.timeNs - origin,
          s.deviceTickUs,
          s.raw,
          s.gapBefore ? "true" : "false",
        ].join(","),
      )
      .join("\n") +
    "\n"
  );
}
export function pathFor(
  samples,
  startNs,
  endNs,
  scale = 0.005,
  width = 1000,
  height = 260,
) {
  if (!samples.length || endNs <= startNs) return "";
  let d = "",
    drawing = false;
  for (const s of samples) {
    if (s.timeNs < startNs || s.timeNs > endNs) continue;
    const x = ((s.timeNs - startNs) / (endNs - startNs)) * width,
      y = height / 2 - (s.raw * scale * height) / 3;
    d +=
      (drawing && !s.gapBefore ? "L" : "M") + x.toFixed(2) + "," + y.toFixed(2);
    drawing = true;
  }
  return d;
}
export function validateMarker(label) {
  const s = String(label).trim();
  if (!s || s.length > 120)
    throw new Error("Use a label between 1 and 120 characters.");
  return s;
}
