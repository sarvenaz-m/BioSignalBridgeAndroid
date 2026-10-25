#!/usr/bin/env python3
"""Read a BioSignal Bridge recording and describe its samples with standard Python."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
import json
import math
from pathlib import Path
import re
import statistics
import sys
from typing import Any

SCHEMA = "biosignal-bridge.session/v1"
HEADER = ["session_id", "channel", "sequence", "timestamp_ns", "device_tick_us", "raw_value", "gap_before"]
COUNTERS = ("receivedPackets", "acceptedPackets", "malformedPackets", "lostPackets", "duplicatePackets", "outOfOrderPackets", "clockResets")
MAX_ROWS = 1_000_000
MAX_CSV_BYTES = 96 * 1024 * 1024
MAX_METADATA_BYTES = 8 * 1024 * 1024


class RecordingError(ValueError):
    """The files do not follow the recording contract."""


@dataclass(frozen=True)
class Sample:
    channel: int
    sequence: int
    timestamp_ns: int
    device_tick_us: int
    raw_value: int
    gap_before: bool


@dataclass(frozen=True)
class Recording:
    metadata: dict[str, Any]
    samples: tuple[Sample, ...]


def integer(value: Any, label: str, low: int = 0, high: int = 2**63 - 1) -> int:
    if isinstance(value, bool):
        raise RecordingError(f"{label} must be an integer.")
    if isinstance(value, str):
        if len(value) > 20 or not re.fullmatch(r"-?(0|[1-9][0-9]*)", value):
            raise RecordingError(f"{label} must be an integer.")
        value = int(value)
    if not isinstance(value, int) or not low <= value <= high:
        raise RecordingError(f"{label} is outside its supported range.")
    return value


def json_integer(value: Any, label: str, low: int = 0, high: int = 2**63 - 1) -> int:
    if isinstance(value, str):
        raise RecordingError(f"{label} must be a JSON integer.")
    return integer(value, label, low, high)


def load_metadata(path: Path) -> dict[str, Any]:
    if path.stat().st_size > MAX_METADATA_BYTES:
        raise RecordingError("Metadata is larger than 8 MiB.")
    try:
        metadata = json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, UnicodeDecodeError) as error:
        raise RecordingError("Metadata must be UTF-8 JSON.") from error
    if not isinstance(metadata, dict) or metadata.get("schema") != SCHEMA:
        raise RecordingError("Recording schema is not supported.")
    if not isinstance(metadata.get("sessionId"), str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", metadata["sessionId"]):
        raise RecordingError("Session identifier is not portable.")
    if metadata.get("source") not in ("PREVIEW", "LIVE", "IMPORTED"):
        raise RecordingError("Recording source is missing or unknown.")
    if metadata.get("timeUnit") != "ns" or json_integer(metadata.get("originNs"), "originNs") != 0:
        raise RecordingError("Export times must use session-relative nanoseconds.")
    json_integer(metadata.get("startedAtEpochMs"), "startedAtEpochMs")
    json_integer(metadata.get("sampleCount"), "sampleCount", 0, MAX_ROWS)
    json_integer(metadata.get("durationNs"), "durationNs")
    channels = metadata.get("channels")
    if not isinstance(channels, list) or not 1 <= len(channels) <= 256:
        raise RecordingError("Provide between one and 256 channel descriptions.")
    channel_ids: set[int] = set()
    for channel in channels:
        if not isinstance(channel, dict):
            raise RecordingError("Each channel needs a description.")
        channel_id = json_integer(channel.get("id"), "channel id", 0, 255)
        if channel_id in channel_ids:
            raise RecordingError("Channel identifiers must be unique.")
        channel_ids.add(channel_id)
        for key, limit in (("name", 120), ("unit", 32)):
            value = channel.get(key)
            if not isinstance(value, str) or not value.strip() or len(value) > limit:
                raise RecordingError(f"Channel {key} is missing or too long.")
        scale = channel.get("scalePerCount")
        if isinstance(scale, bool) or not isinstance(scale, (int, float)) or abs(scale) > sys.float_info.max / 32768 or not math.isfinite(scale) or scale == 0:
            raise RecordingError("Channel scale must be finite and nonzero.")
    markers = metadata.get("markers")
    if not isinstance(markers, list) or len(markers) > 10_000:
        raise RecordingError("Markers must be a list of at most 10,000 entries.")
    marker_ids: set[str] = set()
    for marker in markers:
        if not isinstance(marker, dict):
            raise RecordingError("Each marker needs an identifier, time and label.")
        marker_id = marker.get("id")
        label = marker.get("label")
        if not isinstance(marker_id, str) or not marker_id.strip() or len(marker_id) > 128 or marker_id in marker_ids:
            raise RecordingError("Marker identifiers must be nonempty and unique.")
        marker_ids.add(marker_id)
        json_integer(marker.get("timeNs"), "marker time")
        if not isinstance(label, str) or not label.strip() or len(label) > 240:
            raise RecordingError("Marker labels must contain one to 240 characters.")
    counters = metadata.get("stats")
    if not isinstance(counters, dict):
        raise RecordingError("Packet counters are missing.")
    for key in COUNTERS:
        json_integer(counters.get(key), key)
    if counters["acceptedPackets"] > counters["receivedPackets"] or counters["malformedPackets"] > counters["receivedPackets"]:
        raise RecordingError("Packet counters are inconsistent.")
    return metadata


def read_recording(csv_path: str | Path, metadata_path: str | Path) -> Recording:
    csv_path, metadata_path = Path(csv_path), Path(metadata_path)
    metadata = load_metadata(metadata_path)
    if csv_path.stat().st_size > MAX_CSV_BYTES:
        raise RecordingError("CSV is larger than 96 MiB.")
    channel_ids = {channel["id"] for channel in metadata["channels"]}
    samples: list[Sample] = []
    previous_time: dict[int, int] = {}
    try:
        with csv_path.open(encoding="utf-8", newline="") as stream:
            reader = csv.DictReader(stream)
            if reader.fieldnames != HEADER:
                raise RecordingError("CSV columns do not match the recording contract.")
            for line_number, row in enumerate(reader, start=2):
                if len(samples) >= MAX_ROWS:
                    raise RecordingError("CSV contains more than 1,000,000 samples.")
                if None in row or any(value is None for value in row.values()):
                    raise RecordingError(f"CSV row {line_number} has the wrong number of columns.")
                if row["session_id"] != metadata["sessionId"]:
                    raise RecordingError(f"CSV row {line_number} belongs to another session.")
                channel = integer(row["channel"], "channel", 0, 255)
                if channel not in channel_ids:
                    raise RecordingError(f"CSV row {line_number} uses an undeclared channel.")
                time_ns = integer(row["timestamp_ns"], "timestamp_ns")
                if time_ns < previous_time.get(channel, 0):
                    raise RecordingError(f"Channel {channel} moves backwards in time at row {line_number}.")
                previous_time[channel] = time_ns
                if row["gap_before"] not in ("true", "false"):
                    raise RecordingError(f"CSV row {line_number} has an invalid gap flag.")
                samples.append(Sample(
                    channel=channel,
                    sequence=integer(row["sequence"], "sequence", 0, 65535),
                    timestamp_ns=time_ns,
                    device_tick_us=integer(row["device_tick_us"], "device_tick_us", 0, 2**32 - 1),
                    raw_value=integer(row["raw_value"], "raw_value", -32768, 32767),
                    gap_before=row["gap_before"] == "true",
                ))
    except (UnicodeDecodeError, csv.Error) as error:
        raise RecordingError("CSV must contain well-formed UTF-8 records.") from error
    if len(samples) != metadata["sampleCount"]:
        raise RecordingError("CSV sample count differs from metadata.")
    duration = max((sample.timestamp_ns for sample in samples), default=0)
    if duration != metadata["durationNs"]:
        raise RecordingError("CSV duration differs from metadata.")
    return Recording(metadata, tuple(samples))


def summarize(recording: Recording) -> dict[str, Any]:
    channels = []
    for channel in recording.metadata["channels"]:
        samples = [sample for sample in recording.samples if sample.channel == channel["id"]]
        values = [sample.raw_value * channel["scalePerCount"] for sample in samples]
        intervals = [b.timestamp_ns - a.timestamp_ns for a, b in zip(samples, samples[1:]) if not b.gap_before and b.timestamp_ns > a.timestamp_ns]
        median_ns = statistics.median(intervals) if intervals else None
        gap_count = sum(sample.gap_before for sample in samples)
        magnitude = max((abs(value) for value in values), default=0)
        normalized = [value / magnitude for value in values] if magnitude else [0.0] * len(values)
        channels.append({
            "id": channel["id"], "name": channel["name"], "unit": channel["unit"],
            "sample_count": len(samples),
            "span_seconds": (samples[-1].timestamp_ns - samples[0].timestamp_ns) / 1e9 if samples else 0,
            "median_interval_ms": median_ns / 1e6 if median_ns else None,
            "estimated_rate_hz": 1e9 / median_ns if median_ns else None,
            "minimum": min(values) if values else None,
            "maximum": max(values) if values else None,
            "mean": statistics.fmean(normalized) * magnitude if values else None,
            "rms": math.sqrt(statistics.fmean(value * value for value in normalized)) * magnitude if values else None,
            "population_stddev": statistics.pstdev(normalized) * magnitude if values else None,
            "observed_rate_hz": 1e9 / statistics.fmean(intervals) if intervals else None,
            "interval_jitter_us": statistics.pstdev(intervals) / 1000 if intervals else None,
            "adc_rail_samples": sum(sample.raw_value in (-32768, 32767) for sample in samples),
            "gap_markers": gap_count,
            "segment_count": (1 + sum(sample.gap_before for sample in samples[1:])) if samples else 0,
            "duplicate_timestamps": sum(b.timestamp_ns == a.timestamp_ns for a, b in zip(samples, samples[1:])),
        })
    return {
        "schema": "biosignal-bridge.analysis/v1", "session_id": recording.metadata["sessionId"],
        "source": recording.metadata["source"], "duration_seconds": recording.metadata["durationNs"] / 1e9,
        "sample_count": len(recording.samples), "marker_count": len(recording.metadata["markers"]),
        "packet_counters": recording.metadata["stats"], "channels": channels,
        "notes": ["Rates exclude intervals across marked gaps.", "Lost packet counts do not imply an exact missing sample count.", "Values use the scale stored in the session metadata."],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate a recording and summarize each sensor channel.")
    parser.add_argument("--csv", required=True, type=Path, help="Session sample file")
    parser.add_argument("--metadata", required=True, type=Path, help="Matching session metadata")
    parser.add_argument("--output", type=Path, help="Write the summary here instead of printing it")
    args = parser.parse_args()
    try:
        result = summarize(read_recording(args.csv, args.metadata))
        content = json.dumps(result, indent=2, ensure_ascii=False, allow_nan=False) + "\n"
        if args.output:
            args.output.write_text(content, encoding="utf-8")
        else:
            print(content, end="")
    except (RecordingError, OSError) as error:
        parser.exit(2, f"Cannot read recording: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
