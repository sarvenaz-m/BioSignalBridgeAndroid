# Sensor packet and recording contract

BioSignal Bridge uses a small, open packet format for a compatible peripheral. The preview source sends the same format as the live receiver. It does not reproduce any employer's firmware, electronics or private protocol.

## One packet per notification

Every notification contains exactly 20 bytes. Multibyte integers use little-endian order. No MTU negotiation or application framing is needed for this format.

| Bytes | Field | Meaning |
| --- | --- | --- |
| 0 | Signature | `B1` |
| 1 | Version | `01` |
| 2–3 | Sequence | Unsigned 16-bit counter, shared by all channels |
| 4–7 | Device tick | Unsigned 32-bit microseconds at the first sample |
| 8–9 | Sample period | Unsigned 16-bit microseconds; 1–65,535 |
| 10 | Channel | Unsigned identifier; described in session metadata |
| 11 | Sample count | 1–4 |
| 12–19 | Sample slots | Four signed 16-bit values; unused slots must be zero |

Each sample's raw device tick is `(firstTick + sampleIndex × period) mod 2³²`. Values are raw counts. A channel's recorded `scalePerCount` converts them to its stated unit. Scaling records the session configuration; it does not establish calibration or clinical accuracy.

The sequence advances by one per packet. The peripheral emits packets in nondecreasing first-sample tick order across channels, allowing for the 32-bit clock rollover; samples within packets may overlap in time across channels. Modular sequence steps of 1–32,767 move forward; larger steps are treated as delayed packets. A forward step greater than one adds the missing packet count. It does not estimate a missing sample count, because packets may contain different numbers of samples and channels. Duplicates, delayed packets and malformed notifications are counted separately and produce no samples.

## Time and discontinuities

The first receive timestamp anchors the packet's last sample to the host monotonic clock. Earlier samples use the stated period. Later packets follow the device clock, including its 32-bit rollover. Samples in one channel always move forward in host time; concurrent channels may share a timestamp. If a new packet overlaps that channel's previous samples, its first time is clamped forwards and marked as a gap rather than drawing an artificial connecting line. The alignment does not measure radio latency or correct oscillator drift.

An observed packet loss or a device-clock jump of more than two seconds marks the next sample in each channel with `gapBefore`. Charts start a new line at that sample. A backwards device-clock jump of at least two seconds with a forward modular sequence starts a new aligned segment. If the sequence also appears to move backwards, the processor holds a low-sequence, low-tick candidate until a second coherent packet confirms it. The pending frame is then delivered with the new segment. An isolated candidate cannot reset the clock. If a restarted sequence equals the previous sequence, that first frame is counted as a duplicate; a following forward frame can establish the new clock segment.

The format has no boot identifier. Two delayed packets from an earlier boot can be indistinguishable from a restart; reset detection is therefore a heuristic, not an identity guarantee. A new BLE connection creates a new processor. A silent interval longer than half the device-clock range, or a sequence discontinuity greater than half its range, needs a fresh connection/session rather than an invented wrap count.

All counters saturate at the largest signed 64-bit integer. Live memory limits are owned by the recording layer; the processor stores only a previous timestamp and gap state per channel, plus one pending reset packet.

## CSV and JSON

Each export pairs a sample CSV with JSON metadata. CSV columns are:

```text
session_id,channel,sequence,timestamp_ns,device_tick_us,raw_value,gap_before
```

`timestamp_ns` is relative to the session origin. Android boot timestamps stay in memory. Exported metadata declares `originNs: 0` and `timeUnit: "ns"`. `gap_before` is the literal `true` or `false`. Raw samples remain signed 16-bit integers.

JSON uses schema `biosignal-bridge.session/v1` and includes session identity, source (`PREVIEW`, `LIVE` or `IMPORTED`), start time, sample count, duration, channel names/units/scales, markers and packet counters. Marker times also use session-relative nanoseconds. A sample's scaled value is `raw_value × scalePerCount`. Recording provenance is retained when data is imported.

The decimator works on one channel at a time. It keeps the first and last points plus minimum and maximum values from time-ordered buckets, within the requested point budget. If omitted points contain a gap, the next retained point carries that gap. It never joins a line across an omitted discontinuity. Decimation affects chart points; exported samples retain their full resolution.
