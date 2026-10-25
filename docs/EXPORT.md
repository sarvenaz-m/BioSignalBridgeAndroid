# Session exports

Android exports a ZIP with `session.csv` and `metadata.json`. A public-data preview also includes `provenance.json`. The browser offers CSV and metadata files separately. Both use the same sample columns and metadata schema.

| CSV column | Meaning |
| --- | --- |
| `session_id` | Recording identifier |
| `channel` | Logical channel identifier |
| `sequence` | Packet sequence number shared by the samples in that packet |
| `timestamp_ns` | Session-relative time in nanoseconds |
| `device_tick_us` | Unsigned device counter for the sample, in microseconds |
| `raw_value` | Signed integer sensor value |
| `gap_before` | `true` when the trace should break before this sample |

Metadata uses schema `biosignal-bridge.session/v1`. It records the session source, start time, channel names and units, scale per count, marker labels/times, sample count, duration and delivery counters. Additional native fields record session status and receiver overflow. Portable `originNs` is zero; Android boot timestamps are not exported.

For a channel, the displayed value is `raw_value × scalePerCount`. Live recordings use ADC counts and scale 1; the app does not configure a sensor calibration. The public ECG excerpt uses mV and scale 0.005.

Multiple channels may share a timestamp. Their samples stay ordered within each channel. Android sorts CSV rows by timestamp and then channel; the browser keeps its preview sample order. Use the timestamp, channel and sequence fields to interpret timing rather than assuming that CSV row order records callback arrival order. Packet counters describe accepted, malformed, missing, duplicated and out-of-order packets, plus clock resets; they do not classify the medical waveform.

Native metadata also reports a nominal rate for the first channel and a rate description for each channel. Sample timestamps define the timebase. A change to a channel's packet period stops its active recording so that one session does not silently mix sampling rates.

Event markers annotate the latest recorded sample. They are not a separate measurement of when a physical event occurred. During a silent sensor interval, the last sample time stays unchanged.

The reader in `analysis/` validates the schema and CSV fields before calculating per-channel counts, ranges and timing summaries. Recorded gaps remain explicit and are excluded from regular interval summaries.

## Inspection metadata

Version 1.1 adds an optional `inspection` object to the existing session schema. Its schema is `biosignal-bridge.inspection/v1` and its scope is `whole-recording`. Each entry in `channels` identifies a channel and records `sampleCount`, `minimum`, `maximum`, `mean`, `rms`, `standardDeviation`, `observedRateHz`, `intervalJitterUs`, `gapMarkers`, `segments`, `duplicateTimestamps` and `railSamples`.

Amplitude fields use the channel's units. Standard deviation uses the population definition. Observed rate is the inverse mean of positive adjacent intervals, excluding marked gaps and equal timestamps; jitter uses the population deviation of those same intervals. Empty channels have null amplitude and rate measurements. A single sample has zero amplitude deviation and no observed rate.

The visible-window panel applies these definitions to its current samples. The exported inspection covers the whole recording. ADC rail counts identify raw values of −32768 or 32767; they do not establish whether a sensor was physically saturated.

Readers that understand the original session schema can ignore this additive field. Python continues to report its median-based rate estimate and adds the mean-based observed rate, interval jitter, population amplitude deviation and ADC rail count.
